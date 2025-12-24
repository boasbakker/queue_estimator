package com.queueestimator;

import com.queueestimator.config.QueueEstimatorConfig;
import org.apache.commons.math3.fitting.leastsquares.*;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Multi-formula curve fitter for queue position data.
 * Supports multiple fitting models and returns results for all enabled
 * formulas.
 */
public class MultiFormulaCurveFitter {

    private final List<QueueDataTracker.DataPoint> dataPoints;
    private final QueueEstimatorConfig config;

    // Time scale factor - convert ms to minutes for numerical stability
    private static final double TIME_SCALE = 60000.0;

    // Maximum ETA: 1 week in milliseconds (anything above is considered invalid)
    private static final long MAX_ETA_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Enum for formula types
     */
    public enum FormulaType {
        LINEAR("Linear", "P(t) = A - B·t"),
        QUADRATIC("Quadratic", "P(t) = A - B·t - C·t²"),
        EXPONENTIAL("Exponential", "P(t) = A·e^(-B·t) - C"),
        POWER_LAW("Power Law", "P(t) = A·(t+1)^(-B) + C"),
        LOGARITHMIC("Logarithmic", "P(t) = A - B·ln(t+1)"),
        TANGENT("Tangent", "P(t) = A·tan(B - k·t) - D"),
        HYPERBOLIC("Hyperbolic", "P(t) = A/(t+B) - C");

        public final String displayName;
        public final String formula;

        FormulaType(String displayName, String formula) {
            this.displayName = displayName;
            this.formula = formula;
        }
    }

    /**
     * Result of a single formula fit
     */
    public static class FitResult {
        public final FormulaType type;
        public final double[] params;
        public final double rSquared; // R² coefficient of determination (0 to 1, higher is better)
        public final long etaMs; // Estimated time to position 0 (ms from now), -1 if invalid
        public final boolean valid;

        public FitResult(FormulaType type, double[] params, double rSquared, long etaMs, boolean valid) {
            this.type = type;
            this.params = params;
            this.rSquared = rSquared;
            this.etaMs = etaMs;
            this.valid = valid;
        }

        public String getParamsString() {
            StringBuilder sb = new StringBuilder();
            char[] paramNames = { 'A', 'B', 'C', 'D' };
            for (int i = 0; i < params.length && i < paramNames.length; i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append(paramNames[i]).append("=").append(formatNumber(params[i]));
            }
            return sb.toString();
        }
    }

    /**
     * Container for all fit results
     */
    public static class MultiResult {
        public final List<FitResult> results;
        public final FitResult best; // Best result by R² (if any valid)

        public MultiResult(List<FitResult> results) {
            this.results = results;
            this.best = results.stream()
                    .filter(r -> r.valid && r.etaMs > 0)
                    .max(Comparator.comparingDouble(r -> r.rSquared))
                    .orElse(null);
        }

        public boolean hasValidResult() {
            return best != null;
        }

        public List<FitResult> getValidResults() {
            return results.stream()
                    .filter(r -> r.valid && r.etaMs > 0)
                    .sorted(Comparator.comparingDouble(r -> -r.rSquared))
                    .toList();
        }
    }

    public MultiFormulaCurveFitter(List<QueueDataTracker.DataPoint> dataPoints) {
        this.dataPoints = dataPoints;
        this.config = QueueEstimatorConfig.getInstance();
    }

    /**
     * Fit all enabled formulas and return results
     */
    public MultiResult fitAll() {
        List<FitResult> results = new ArrayList<>();

        if (dataPoints.size() < 3) {
            QueueEstimatorMod.LOGGER.warn("Not enough data points for fitting: {}", dataPoints.size());
            return new MultiResult(results);
        }

        // Prepare data for ALL formulas
        final int n = dataPoints.size();
        final double[] times = new double[n];
        final double[] positions = new double[n];

        long t0 = dataPoints.get(0).timestamp;
        double meanPosition = 0;
        for (int i = 0; i < n; i++) {
            times[i] = (dataPoints.get(i).timestamp - t0) / TIME_SCALE;
            positions[i] = dataPoints.get(i).position;
            meanPosition += positions[i];
        }
        meanPosition /= n;

        final double finalMeanPosition = meanPosition;
        long currentTimeMs = System.currentTimeMillis();
        long sessionStartTime = currentTimeMs - dataPoints.get(n - 1).timestamp;

        // Prepare WINDOWED data for linear fit
        int linearWindowMinutes = config.getLinearWindowMinutes();
        long windowMs = linearWindowMinutes * 60 * 1000L;
        long cutoffTime = dataPoints.get(n - 1).timestamp - windowMs;

        // Find first index within the window
        int windowStartIdx = 0;
        for (int i = 0; i < n; i++) {
            if (dataPoints.get(i).timestamp >= cutoffTime) {
                windowStartIdx = i;
                break;
            }
        }

        // Create windowed arrays for linear fit
        int windowN = n - windowStartIdx;
        final double[] windowedTimes = new double[windowN];
        final double[] windowedPositions = new double[windowN];
        double windowedMeanPosition = 0;

        for (int i = 0; i < windowN; i++) {
            windowedTimes[i] = (dataPoints.get(windowStartIdx + i).timestamp - t0) / TIME_SCALE;
            windowedPositions[i] = dataPoints.get(windowStartIdx + i).position;
            windowedMeanPosition += windowedPositions[i];
        }
        windowedMeanPosition /= windowN;

        if (windowN < n) {
            QueueEstimatorMod.LOGGER.info("Linear fit using windowed data: {} of {} points (last {} min)",
                    windowN, n, linearWindowMinutes);
        }

        // Fit LINEAR FIRST to get baseline ETA for validation
        FitResult linearResult = null;
        long linearEtaMs = -1;

        if (config.isLinearEnabled()) {
            linearResult = fitLinear(windowedTimes, windowedPositions, windowedMeanPosition, sessionStartTime);
            results.add(linearResult);
            if (linearResult.valid && linearResult.etaMs > 0) {
                linearEtaMs = linearResult.etaMs;
            }
        }

        // Fit other formulas using FULL data (they can capture long-term trends better)
        if (config.isQuadraticEnabled()) {
            FitResult result = fitQuadratic(times, positions, finalMeanPosition, sessionStartTime);
            result = validateAgainstLinear(result, linearEtaMs);
            results.add(result);
        }

        if (config.isExponentialEnabled()) {
            FitResult result = fitExponential(times, positions, finalMeanPosition, sessionStartTime);
            result = validateAgainstLinear(result, linearEtaMs);
            results.add(result);
        }

        if (config.isPowerLawEnabled()) {
            FitResult result = fitPowerLaw(times, positions, finalMeanPosition, sessionStartTime);
            result = validateAgainstLinear(result, linearEtaMs);
            results.add(result);
        }

        if (config.isLogarithmicEnabled()) {
            FitResult result = fitLogarithmic(times, positions, finalMeanPosition, sessionStartTime);
            result = validateAgainstLinear(result, linearEtaMs);
            results.add(result);
        }

        if (config.isTangentEnabled()) {
            FitResult result = fitTangent(times, positions, finalMeanPosition, sessionStartTime);
            result = validateAgainstLinear(result, linearEtaMs);
            results.add(result);
        }

        if (config.isHyperbolicEnabled()) {
            FitResult result = fitHyperbolic(times, positions, finalMeanPosition, sessionStartTime);
            result = validateAgainstLinear(result, linearEtaMs);
            results.add(result);
        }

        return new MultiResult(results);
    }

    /**
     * Validate a non-linear fit result against the linear baseline.
     * If a nonlinear fit predicts a shorter ETA than linear, it's unrealistic
     * (the queue can't drain faster than linear extrapolation).
     */
    private FitResult validateAgainstLinear(FitResult result, long linearEtaMs) {
        if (!result.valid || result.etaMs <= 0 || linearEtaMs <= 0) {
            return result;
        }

        // If nonlinear ETA is less than linear ETA, reject it
        if (result.etaMs < linearEtaMs) {
            QueueEstimatorMod.LOGGER.warn(
                    "{}: ETA of {} ms is less than linear ETA of {} ms, rejecting (unrealistic acceleration)",
                    result.type.displayName, result.etaMs, linearEtaMs);
            return new FitResult(result.type, result.params, result.rSquared, -1, false);
        }

        return result;
    }

    /**
     * Linear fit: P(t) = A - B*t
     * Simple and robust, good baseline
     */
    private FitResult fitLinear(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            // Analytical solution using least squares
            double sumT = 0, sumP = 0, sumT2 = 0, sumTP = 0;
            for (int i = 0; i < n; i++) {
                sumT += times[i];
                sumP += positions[i];
                sumT2 += times[i] * times[i];
                sumTP += times[i] * positions[i];
            }

            double denom = n * sumT2 - sumT * sumT;
            if (Math.abs(denom) < 1e-10) {
                return new FitResult(FormulaType.LINEAR, new double[] { 0, 0 }, 0, -1, false);
            }

            double B = (n * sumTP - sumT * sumP) / denom;
            double A = (sumP - B * sumT) / n;

            // We want decreasing position, so B should be negative in our formula P = A -
            // B*t
            // Actually we defined it as A - B*t, so B should be positive for decreasing
            B = -B; // Convert from slope to rate

            // Calculate R² (coefficient of determination)
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            for (int i = 0; i < n; i++) {
                double predicted = A - B * times[i];
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            // Calculate ETA: when does A - B*t = 0? t = A/B
            long etaMs = -1;
            if (B > 0 && A > 0) {
                double tZeroMinutes = A / B;
                etaMs = (long) (tZeroMinutes * TIME_SCALE) - dataPoints.get(dataPoints.size() - 1).timestamp;
                if (etaMs < 0)
                    etaMs = -1;
            }

            QueueEstimatorMod.LOGGER.info("Linear fit: A={}, B={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), String.format("%.4f", rSquared), etaMs);

            return new FitResult(FormulaType.LINEAR, new double[] { A, B }, rSquared, validateEta(etaMs, "Linear"),
                    true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Linear fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.LINEAR, new double[] { 0, 0 }, 0, -1, false);
        }
    }

    /**
     * Quadratic fit: P(t) = A + B*t + C*t²
     * We fit a standard polynomial and then interpret: for decreasing queue,
     * we expect B < 0 and possibly C > 0 (deceleration) or C < 0 (acceleration)
     */
    private FitResult fitQuadratic(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            QueueEstimatorMod.LOGGER.info("Quadratic fit starting with {} points", n);

            // Use normal equations for polynomial fitting: P = A + B*t + C*t²
            // This is the standard form - we'll interpret later
            double s0 = n, s1 = 0, s2 = 0, s3 = 0, s4 = 0;
            double sp0 = 0, sp1 = 0, sp2 = 0;

            for (int i = 0; i < n; i++) {
                double t = times[i];
                double t2 = t * t;
                double t3 = t2 * t;
                double t4 = t3 * t;
                double p = positions[i];

                s1 += t;
                s2 += t2;
                s3 += t3;
                s4 += t4;
                sp0 += p;
                sp1 += t * p;
                sp2 += t2 * p;
            }

            QueueEstimatorMod.LOGGER.info("Quadratic sums: s0={}, s1={}, s2={}, s3={}, s4={}",
                    s0, formatNumber(s1), formatNumber(s2), formatNumber(s3), formatNumber(s4));
            QueueEstimatorMod.LOGGER.info("Quadratic position sums: sp0={}, sp1={}, sp2={}",
                    formatNumber(sp0), formatNumber(sp1), formatNumber(sp2));

            // Solve 3x3 system: [s0 s1 s2] [A] [sp0]
            // [s1 s2 s3] [B] = [sp1]
            // [s2 s3 s4] [C] [sp2]
            // Using Cramer's rule
            double det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s2 * s3) + s2 * (s1 * s3 - s2 * s2);

            QueueEstimatorMod.LOGGER.info("Quadratic determinant: {}", formatNumber(det));

            if (Math.abs(det) < 1e-10) {
                QueueEstimatorMod.LOGGER.info("Quadratic fit failed: determinant too small ({})", det);
                return new FitResult(FormulaType.QUADRATIC, new double[] { 0, 0, 0 }, 0, -1, false);
            }

            // Cramer's rule for A (replace first column with sp vector)
            double detA = sp0 * (s2 * s4 - s3 * s3) - s1 * (sp1 * s4 - sp2 * s3) + s2 * (sp1 * s3 - sp2 * s2);
            // Cramer's rule for B (replace second column)
            double detB = s0 * (sp1 * s4 - sp2 * s3) - sp0 * (s1 * s4 - s2 * s3) + s2 * (s1 * sp2 - s2 * sp1);
            // Cramer's rule for C (replace third column)
            double detC = s0 * (s2 * sp2 - s3 * sp1) - s1 * (s1 * sp2 - s2 * sp1) + sp0 * (s1 * s3 - s2 * s2);

            double A = detA / det;
            double B = detB / det;
            double C = detC / det;

            QueueEstimatorMod.LOGGER.info("Quadratic raw coefficients: A={}, B={}, C={}",
                    formatNumber(A), formatNumber(B), formatNumber(C));

            // Calculate R² (coefficient of determination)
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            for (int i = 0; i < n; i++) {
                double predicted = A + B * times[i] + C * times[i] * times[i];
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            QueueEstimatorMod.LOGGER.info("Quadratic R²: {}", String.format("%.4f", rSquared));

            // Calculate ETA: solve A + B*t + C*t² = 0 using quadratic formula
            // C*t² + B*t + A = 0 => t = (-B ± sqrt(B² - 4CA)) / (2C)
            long etaMs = -1;
            double currentT = times[n - 1];

            if (Math.abs(C) > 1e-10) {
                double discriminant = B * B - 4 * C * A;
                QueueEstimatorMod.LOGGER.info("Quadratic discriminant for ETA: {}", formatNumber(discriminant));

                if (discriminant >= 0) {
                    double sqrtDisc = Math.sqrt(discriminant);
                    double t1 = (-B + sqrtDisc) / (2 * C);
                    double t2 = (-B - sqrtDisc) / (2 * C);

                    QueueEstimatorMod.LOGGER.info("Quadratic roots: t1={}, t2={}", formatNumber(t1), formatNumber(t2));

                    // Pick the positive root that's in the future
                    double tZero = -1;
                    if (t1 > currentT)
                        tZero = t1;
                    if (t2 > currentT && (tZero < 0 || t2 < tZero))
                        tZero = t2;

                    if (tZero > 0) {
                        // Convert from time since start to time remaining
                        double timeRemaining = tZero - currentT;
                        etaMs = (long) (timeRemaining * TIME_SCALE);
                        QueueEstimatorMod.LOGGER.info("Quadratic ETA: tZero={}, timeRemaining={} min, etaMs={}",
                                formatNumber(tZero), formatNumber(timeRemaining), etaMs);
                    } else {
                        QueueEstimatorMod.LOGGER.warn("Quadratic: both roots are in the past, no valid ETA");
                    }
                } else {
                    // Negative discriminant - parabola doesn't cross zero
                    if (C > 0) {
                        QueueEstimatorMod.LOGGER
                                .warn("Quadratic: parabola opens upward and never reaches zero (no real roots)");
                    } else {
                        QueueEstimatorMod.LOGGER
                                .warn("Quadratic: parabola opens downward but minimum is above zero (no real roots)");
                    }
                }
            } else if (Math.abs(B) > 1e-10) {
                // Degenerate to linear: B*t + A = 0 => t = -A/B
                double tZero = -A / B;
                if (tZero > currentT) {
                    double timeRemaining = tZero - currentT;
                    etaMs = (long) (timeRemaining * TIME_SCALE);
                }
                QueueEstimatorMod.LOGGER.info("Quadratic degenerated to linear, tZero={}", formatNumber(tZero));
            }

            QueueEstimatorMod.LOGGER.info("Quadratic fit complete: A={}, B={}, C={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.4f", rSquared), etaMs);

            return new FitResult(FormulaType.QUADRATIC, new double[] { A, B, C }, rSquared,
                    validateEta(etaMs, "Quadratic"), true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Quadratic fit failed with exception: {}", e.getMessage());
            e.printStackTrace();
            return new FitResult(FormulaType.QUADRATIC, new double[] { 0, 0, 0 }, 0, -1, false);
        }
    }

    /**
     * Shifted exponential fit: P(t) = A * e^(-B*t) - C
     * Uses Levenberg-Marquardt optimization
     */
    private FitResult fitExponential(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            // Initial guesses
            double A = positions[0] * 1.2; // Slightly above initial position
            double B = 0.1; // Moderate decay rate
            double C = 0; // Start with no shift

            // Better initial B estimate
            if (positions[0] > positions[n - 1] && positions[n - 1] > 0 && times[n - 1] > 0) {
                B = Math.log(positions[0] / Math.max(1, positions[n - 1])) / times[n - 1];
                B = Math.max(0.001, Math.min(B, 2.0));
            }

            double[] initialGuess = { A, B, C };

            // Parameter validator
            ParameterValidator validator = params -> {
                double pA = Math.max(1.0, params.getEntry(0));
                double pB = Math.max(1e-6, Math.min(params.getEntry(1), 5.0));
                double pC = Math.max(0.0, params.getEntry(2));
                return new ArrayRealVector(new double[] { pA, pB, pC });
            };

            // Model function
            MultivariateJacobianFunction model = createExponentialModel(times, n);

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(initialGuess)
                    .model(model)
                    .target(positions)
                    .parameterValidator(validator)
                    .maxEvaluations(3000)
                    .maxIterations(1000)
                    .build();

            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer()
                    .withCostRelativeTolerance(1e-8)
                    .withParameterRelativeTolerance(1e-8);

            LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(problem);
            double[] result = optimum.getPoint().toArray();

            A = result[0];
            B = result[1];
            C = result[2];

            // Calculate R² (coefficient of determination)
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            for (int i = 0; i < n; i++) {
                double predicted = A * Math.exp(-B * times[i]) - C;
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            // Calculate ETA: A * e^(-B*t) = C => t = ln(A/C) / B
            long etaMs = -1;
            if (A > C && C > 0 && B > 0) {
                double tZero = Math.log(A / C) / B;
                etaMs = (long) (tZero * TIME_SCALE) - dataPoints.get(dataPoints.size() - 1).timestamp;
                if (etaMs < 0)
                    etaMs = -1;
            } else if (C <= 0 && B > 0 && A > 0) {
                // Without shift, use linear approximation at current point
                double currentT = times[n - 1];
                double currentP = positions[n - 1];
                double slope = -A * B * Math.exp(-B * currentT); // derivative
                if (slope < 0) {
                    double tRemaining = -currentP / slope;
                    etaMs = (long) (tRemaining * TIME_SCALE);
                }
            }

            QueueEstimatorMod.LOGGER.info("Exponential fit: A={}, B={}, C={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.4f", rSquared), etaMs);

            return new FitResult(FormulaType.EXPONENTIAL, new double[] { A, B, C }, rSquared,
                    validateEta(etaMs, "Exponential"), true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Exponential fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.EXPONENTIAL, new double[] { 0, 0, 0 }, 0, -1, false);
        }
    }

    private MultivariateJacobianFunction createExponentialModel(double[] times, int n) {
        return point -> {
            double A = point.getEntry(0);
            double B = point.getEntry(1);
            double C = point.getEntry(2);

            RealVector value = new ArrayRealVector(n);
            RealMatrix jacobian = new Array2DRowRealMatrix(n, 3);

            for (int i = 0; i < n; i++) {
                double t = times[i];
                double expTerm = Math.exp(-B * t);
                if (Double.isNaN(expTerm) || Double.isInfinite(expTerm)) {
                    expTerm = (B * t > 0) ? 0.0 : 1.0;
                }

                value.setEntry(i, A * expTerm - C);
                jacobian.setEntry(i, 0, expTerm);
                jacobian.setEntry(i, 1, -A * t * expTerm);
                jacobian.setEntry(i, 2, -1.0);
            }

            return new Pair<>(value, jacobian);
        };
    }

    /**
     * Power law fit: P(t) = A * (t+1)^(-B) + C
     */
    private FitResult fitPowerLaw(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            // Initial guesses
            double initA = positions[0] * 2;
            double initB = 0.5;
            double initC = 0;

            double[] initialGuess = { initA, initB, initC };

            ParameterValidator validator = params -> {
                double pA = Math.max(1.0, params.getEntry(0));
                double pB = Math.max(0.01, Math.min(params.getEntry(1), 3.0));
                double pC = Math.max(0.0, params.getEntry(2));
                return new ArrayRealVector(new double[] { pA, pB, pC });
            };

            MultivariateJacobianFunction model = point -> {
                double pA = point.getEntry(0);
                double pB = point.getEntry(1);
                double pC = point.getEntry(2);

                RealVector value = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, 3);

                for (int i = 0; i < n; i++) {
                    double t = times[i];
                    double tPlus1 = t + 1;
                    double powerTerm = Math.pow(tPlus1, -pB);
                    if (Double.isNaN(powerTerm) || Double.isInfinite(powerTerm)) {
                        powerTerm = 0;
                    }

                    value.setEntry(i, pA * powerTerm + pC);
                    jacobian.setEntry(i, 0, powerTerm);
                    jacobian.setEntry(i, 1, -pA * powerTerm * Math.log(tPlus1));
                    jacobian.setEntry(i, 2, 1.0);
                }

                return new Pair<>(value, jacobian);
            };

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(initialGuess)
                    .model(model)
                    .target(positions)
                    .parameterValidator(validator)
                    .maxEvaluations(3000)
                    .maxIterations(1000)
                    .build();

            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer()
                    .withCostRelativeTolerance(1e-8)
                    .withParameterRelativeTolerance(1e-8);

            LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(problem);
            double[] result = optimum.getPoint().toArray();

            double A = result[0];
            double B = result[1];
            double C = result[2];

            // Calculate R² (coefficient of determination)
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            for (int i = 0; i < n; i++) {
                double predicted = A * Math.pow(times[i] + 1, -B) + C;
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            // Calculate ETA: A*(t+1)^(-B) + C = 0 => (t+1)^(-B) = -C/A
            // Only valid if C < 0, which means (t+1)^(-B) = -C/A > 0
            // Then t+1 = (-C/A)^(-1/B) = (-A/C)^(1/B), so t = (-A/C)^(1/B) - 1
            long etaMs = -1;
            if (C < 0 && A > 0 && B > 0) {
                double ratio = -A / C; // This is positive since C < 0
                double tZero = Math.pow(ratio, 1.0 / B) - 1;
                double currentT = times[n - 1];
                if (tZero > currentT) {
                    double timeRemaining = tZero - currentT;
                    etaMs = (long) (timeRemaining * TIME_SCALE);
                    QueueEstimatorMod.LOGGER.info("Power law ETA: tZero={}, timeRemaining={} min",
                            formatNumber(tZero), formatNumber(timeRemaining));
                } else {
                    QueueEstimatorMod.LOGGER.warn("Power law: root is in the past, no valid ETA");
                }
            } else {
                QueueEstimatorMod.LOGGER.warn(
                        "Power law: no closed-form ETA exists (C={} must be < 0 for curve to cross zero)",
                        formatNumber(C));
            }

            QueueEstimatorMod.LOGGER.info("Power law fit: A={}, B={}, C={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.4f", rSquared), etaMs);

            return new FitResult(FormulaType.POWER_LAW, new double[] { A, B, C }, rSquared,
                    validateEta(etaMs, "Power Law"), true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Power law fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.POWER_LAW, new double[] { 0, 0, 0 }, 0, -1, false);
        }
    }

    /**
     * Logarithmic fit: P(t) = A - B * ln(t + 1)
     */
    private FitResult fitLogarithmic(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            // Transform: let x = ln(t+1), then P = A - B*x (linear in transformed space)
            double sumX = 0, sumP = 0, sumX2 = 0, sumXP = 0;
            for (int i = 0; i < n; i++) {
                double x = Math.log(times[i] + 1);
                sumX += x;
                sumP += positions[i];
                sumX2 += x * x;
                sumXP += x * positions[i];
            }

            double denom = n * sumX2 - sumX * sumX;
            if (Math.abs(denom) < 1e-10) {
                return new FitResult(FormulaType.LOGARITHMIC, new double[] { 0, 0 }, 0, -1, false);
            }

            double B = -(n * sumXP - sumX * sumP) / denom; // Note the negative for our formula
            double A = (sumP + B * sumX) / n;

            // Calculate R² (coefficient of determination)
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            for (int i = 0; i < n; i++) {
                double predicted = A - B * Math.log(times[i] + 1);
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            // Calculate ETA: A - B*ln(t+1) = 0 => ln(t+1) = A/B => t = e^(A/B) - 1
            // tZero is in scaled time (minutes), currentT is also in scaled time
            // 1 week = 10080 minutes, ln(10080) ≈ 9.2, so ratio > 10 means ETA > 1 week
            long etaMs = -1;
            if (B > 0 && A > 0) {
                double ratio = A / B;
                if (ratio > 15) {
                    // e^15 ≈ 3.3 million minutes, way beyond reasonable
                    QueueEstimatorMod.LOGGER.warn(
                            "Logarithmic: ratio A/B = {} is too large (e^{} would overflow), no valid ETA",
                            formatNumber(ratio), formatNumber(ratio));
                } else {
                    double tZero = Math.exp(ratio) - 1; // absolute time in minutes when P=0
                    double currentT = times[n - 1]; // current time in minutes
                    if (tZero > currentT) {
                        double timeRemaining = tZero - currentT; // remaining time in minutes
                        etaMs = (long) (timeRemaining * TIME_SCALE);
                    } else {
                        QueueEstimatorMod.LOGGER.warn(
                                "Logarithmic: tZero={} is in the past (currentT={}), no valid ETA",
                                formatNumber(tZero), formatNumber(currentT));
                    }
                }
            } else {
                QueueEstimatorMod.LOGGER.warn("Logarithmic: invalid parameters A={}, B={} (both must be > 0)",
                        formatNumber(A), formatNumber(B));
            }

            QueueEstimatorMod.LOGGER.info("Logarithmic fit: A={}, B={}, ratio={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), formatNumber(A / B), String.format("%.4f", rSquared), etaMs);

            return new FitResult(FormulaType.LOGARITHMIC, new double[] { A, B }, rSquared,
                    validateEta(etaMs, "Logarithmic"), true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Logarithmic fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.LOGARITHMIC, new double[] { 0, 0 }, 0, -1, false);
        }
    }

    /**
     * Tangent fit: P(t) = A * tan(B - k*t) - D
     * This is the solution to dP/dt = -c*(P + a*P²) where the P² term
     * represents position-dependent dropout rates.
     * Uses Levenberg-Marquardt optimization with 4 parameters.
     */
    private FitResult fitTangent(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            if (n < 5) {
                QueueEstimatorMod.LOGGER.info("Tangent fit needs at least 5 points, got {}", n);
                return new FitResult(FormulaType.TANGENT, new double[] { 0, 0, 0, 0 }, 0, -1, false);
            }

            // Initial guesses for A, B, k, D (all must be > 0)
            // P(t) = A * tan(B - k*t) - D
            // At t=0: P(0) = A * tan(B) - D
            // At t_end: P approaches 0 when B - k*t_end approaches arctan(D/A)
            //
            // Key constraint: B - k*t must stay in (-π/2 + margin, π/2 - margin) for all t
            // At t=0: angle = B, so B < π/2 - margin
            // At t=t_max: angle = B - k*t_max > -π/2 + margin
            // Therefore: k < (B + π/2 - margin) / t_max

            double tMax = times[n - 1];
            double safeMargin = 0.3; // Stay 0.3 radians (~17°) away from asymptotes

            double initB = Math.PI / 4; // Start at 45 degrees (0.785 rad)
            // Ensure k doesn't push angles past the asymptote: k*t_max < B + π/2 - margin
            double maxK = (initB + Math.PI / 2 - safeMargin) / Math.max(tMax, 1.0);
            double initK = Math.min(0.1, maxK * 0.5); // Conservative initial k
            double initA = positions[0] / 2;
            double initD = positions[0] / 4; // Positive offset

            double[] initialGuess = { initA, initB, initK, initD };

            // Capture tMax for use in validator
            final double finalTMax = tMax;
            final double finalSafeMargin = safeMargin;

            ParameterValidator validator = params -> {
                double pA = Math.max(1.0, params.getEntry(0)); // A > 0 (amplitude)
                double pB = Math.max(0.1, Math.min(params.getEntry(1), Math.PI / 2 - finalSafeMargin)); // Keep B safe
                // Constrain k so that B - k*t_max > -π/2 + margin
                double maxAllowedK = (pB + Math.PI / 2 - finalSafeMargin) / Math.max(finalTMax, 1.0);
                double pK = Math.max(1e-6, Math.min(params.getEntry(2), maxAllowedK));
                double pD = Math.max(0.0, params.getEntry(3)); // D >= 0 (offset)
                return new ArrayRealVector(new double[] { pA, pB, pK, pD });
            };

            MultivariateJacobianFunction model = point -> {
                double pA = point.getEntry(0);
                double pB = point.getEntry(1);
                double pK = point.getEntry(2);
                double pD = point.getEntry(3);

                RealVector value = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, 4);

                for (int i = 0; i < n; i++) {
                    double t = times[i];
                    double angle = pB - pK * t;

                    // Avoid singularities near ±π/2
                    if (Math.abs(angle) > Math.PI / 2 - 0.1) {
                        angle = Math.signum(angle) * (Math.PI / 2 - 0.1);
                    }

                    double tanVal = Math.tan(angle);
                    double sec2Val = 1 + tanVal * tanVal; // sec²(x) = 1 + tan²(x)

                    // P(t) = A * tan(B - k*t) - D
                    value.setEntry(i, pA * tanVal - pD);

                    // Jacobian:
                    // dP/dA = tan(B - k*t)
                    // dP/dB = A * sec²(B - k*t)
                    // dP/dk = -A * t * sec²(B - k*t)
                    // dP/dD = -1
                    jacobian.setEntry(i, 0, tanVal);
                    jacobian.setEntry(i, 1, pA * sec2Val);
                    jacobian.setEntry(i, 2, -pA * t * sec2Val);
                    jacobian.setEntry(i, 3, -1.0);
                }

                return new Pair<>(value, jacobian);
            };

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(initialGuess)
                    .model(model)
                    .target(positions)
                    .parameterValidator(validator)
                    .maxEvaluations(5000)
                    .maxIterations(2000)
                    .build();

            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer()
                    .withCostRelativeTolerance(1e-8)
                    .withParameterRelativeTolerance(1e-8);

            LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(problem);
            double[] result = optimum.getPoint().toArray();

            double A = result[0];
            double B = result[1];
            double k = result[2];
            double D = result[3];

            // Calculate R² (coefficient of determination)
            // Use the same angle clamping as the model to avoid tan() explosions
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            boolean hasInvalidAngle = false;
            for (int i = 0; i < n; i++) {
                double angle = B - k * times[i];

                // Check if any angle is dangerously close to asymptote
                if (Math.abs(angle) > Math.PI / 2 - 0.2) {
                    hasInvalidAngle = true;
                }

                // Clamp angle to avoid tan() explosion (same as model)
                if (Math.abs(angle) > Math.PI / 2 - 0.1) {
                    angle = Math.signum(angle) * (Math.PI / 2 - 0.1);
                }

                double predicted = A * Math.tan(angle) - D;
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            // Reject if fit required angle clamping (parameters are outside valid range)
            if (hasInvalidAngle) {
                QueueEstimatorMod.LOGGER.warn(
                        "Tangent: fit has angles too close to asymptote (B={}, k={}, t_max={}), rejecting",
                        formatNumber(B), formatNumber(k), formatNumber(times[n - 1]));
                return new FitResult(FormulaType.TANGENT, new double[] { A, B, k, D }, rSquared, -1, false);
            }

            // Calculate ETA: A * tan(B - k*t) - D = 0
            // tan(B - k*t) = D/A
            // B - k*t = arctan(D/A)
            // t = (B - arctan(D/A)) / k
            long etaMs = -1;
            if (k > 0 && A != 0) {
                double tZero = (B - Math.atan(D / A)) / k;
                double currentT = times[n - 1];
                if (tZero > currentT) {
                    double timeRemaining = tZero - currentT;
                    etaMs = (long) (timeRemaining * TIME_SCALE);
                }
            }

            QueueEstimatorMod.LOGGER.info("Tangent fit: A={}, B={}, k={}, D={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), formatNumber(k), formatNumber(D),
                    String.format("%.4f", rSquared), etaMs);

            // Reject if R² <= 0 (fit is worse than using the mean)
            if (rSquared <= 0) {
                QueueEstimatorMod.LOGGER.warn("Tangent: R²={} is <= 0, rejecting (fit worse than mean)",
                        String.format("%.4f", rSquared));
                return new FitResult(FormulaType.TANGENT, new double[] { A, B, k, D }, rSquared, -1, false);
            }

            return new FitResult(FormulaType.TANGENT, new double[] { A, B, k, D }, rSquared,
                    validateEta(etaMs, "Tangent"), true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Tangent fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.TANGENT, new double[] { 0, 0, 0, 0 }, 0, -1, false);
        }
    }

    /**
     * Hyperbolic fit: P(t) = A/(t+B) - C
     * This models queue progression where the rate slows down over time.
     */
    private FitResult fitHyperbolic(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            // Initial guesses
            // At t=0: P(0) = A/B - C
            // As t→∞: P → -C (should be 0 or small positive for valid queue)
            double initA = positions[0] * times[n - 1]; // Rough estimate
            double initB = 1.0;
            double initC = 0;

            double[] initialGuess = { initA, initB, initC };

            ParameterValidator validator = params -> {
                double pA = Math.max(1.0, params.getEntry(0));
                double pB = Math.max(0.01, params.getEntry(1));
                double pC = Math.max(0.0, params.getEntry(2));
                return new ArrayRealVector(new double[] { pA, pB, pC });
            };

            MultivariateJacobianFunction model = point -> {
                double pA = point.getEntry(0);
                double pB = point.getEntry(1);
                double pC = point.getEntry(2);

                RealVector value = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, 3);

                for (int i = 0; i < n; i++) {
                    double t = times[i];
                    double denom = t + pB;
                    if (denom < 0.01)
                        denom = 0.01; // Prevent division issues

                    // P(t) = A/(t+B) - C
                    value.setEntry(i, pA / denom - pC);

                    // Jacobian:
                    // dP/dA = 1/(t+B)
                    // dP/dB = -A/(t+B)²
                    // dP/dC = -1
                    jacobian.setEntry(i, 0, 1.0 / denom);
                    jacobian.setEntry(i, 1, -pA / (denom * denom));
                    jacobian.setEntry(i, 2, -1.0);
                }

                return new Pair<>(value, jacobian);
            };

            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(initialGuess)
                    .model(model)
                    .target(positions)
                    .parameterValidator(validator)
                    .maxEvaluations(3000)
                    .maxIterations(1000)
                    .build();

            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer()
                    .withCostRelativeTolerance(1e-8)
                    .withParameterRelativeTolerance(1e-8);

            LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(problem);
            double[] result = optimum.getPoint().toArray();

            double A = result[0];
            double B = result[1];
            double C = result[2];

            // Calculate R² (coefficient of determination)
            double sse = 0; // Sum of squared errors
            double sst = 0; // Total sum of squares
            for (int i = 0; i < n; i++) {
                double predicted = A / (times[i] + B) - C;
                double error = positions[i] - predicted;
                sse += error * error;
                double devFromMean = positions[i] - meanPos;
                sst += devFromMean * devFromMean;
            }
            double rSquared = (sst > 0) ? 1.0 - (sse / sst) : 0;

            // Calculate ETA: A/(t+B) - C = 0
            // A/(t+B) = C
            // t + B = A/C
            // t = A/C - B
            long etaMs = -1;
            if (C > 0 && A > 0) {
                double tZero = A / C - B;
                double currentT = times[n - 1];
                if (tZero > currentT) {
                    double timeRemaining = tZero - currentT;
                    etaMs = (long) (timeRemaining * TIME_SCALE);
                }
            } else if (C <= 0) {
                // Without offset, use tangent approximation
                double currentT = times[n - 1];
                double currentP = positions[n - 1];
                double slope = -A / ((currentT + B) * (currentT + B));
                if (slope < 0 && currentP > 0) {
                    double tRemaining = -currentP / slope;
                    etaMs = (long) (tRemaining * TIME_SCALE);
                }
            }

            QueueEstimatorMod.LOGGER.info("Hyperbolic fit: A={}, B={}, C={}, R²={}, etaMs={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.4f", rSquared), etaMs);

            // Reject if R² <= 0 (fit is worse than using the mean)
            if (rSquared <= 0) {
                QueueEstimatorMod.LOGGER.warn("Hyperbolic: R²={} is <= 0, rejecting (fit worse than mean)",
                        String.format("%.4f", rSquared));
                return new FitResult(FormulaType.HYPERBOLIC, new double[] { A, B, C }, rSquared, -1, false);
            }

            return new FitResult(FormulaType.HYPERBOLIC, new double[] { A, B, C }, rSquared,
                    validateEta(etaMs, "Hyperbolic"), true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.info("Hyperbolic fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.HYPERBOLIC, new double[] { 0, 0, 0 }, 0, -1, false);
        }
    }

    /**
     * Validates ETA - returns -1 if ETA is negative or exceeds 1 week
     */
    private static long validateEta(long etaMs, String formulaName) {
        if (etaMs <= 0) {
            return -1;
        }
        if (etaMs > MAX_ETA_MS) {
            QueueEstimatorMod.LOGGER.warn("{}: ETA of {} ms exceeds 1 week threshold, rejecting",
                    formulaName, etaMs);
            return -1;
        }
        return etaMs;
    }

    private static String formatNumber(double value) {
        double absValue = Math.abs(value);
        if (absValue >= 1e4 || (absValue > 0 && absValue < 1e-4)) {
            return String.format("%.3e", value);
        }
        return String.format("%.4f", value);
    }
}
