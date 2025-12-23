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

    /**
     * Enum for formula types
     */
    public enum FormulaType {
        LINEAR("Linear", "P(t) = A - B·t"),
        QUADRATIC("Quadratic", "P(t) = A - B·t - C·t²"),
        EXPONENTIAL("Exponential", "P(t) = A·e^(-B·t) - C"),
        POWER_LAW("Power Law", "P(t) = A·(t+1)^(-B) + C"),
        LOGARITHMIC("Logarithmic", "P(t) = A - B·ln(t+1)");

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
        public final double relativeRMSE; // RMSE as percentage of mean position
        public final long etaMs; // Estimated time to position 0 (ms from now), -1 if invalid
        public final boolean valid;

        public FitResult(FormulaType type, double[] params, double relativeRMSE, long etaMs, boolean valid) {
            this.type = type;
            this.params = params;
            this.relativeRMSE = relativeRMSE;
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
        public final FitResult best; // Best result by RMSE (if any valid)

        public MultiResult(List<FitResult> results) {
            this.results = results;
            this.best = results.stream()
                    .filter(r -> r.valid && r.etaMs > 0)
                    .min(Comparator.comparingDouble(r -> r.relativeRMSE))
                    .orElse(null);
        }

        public boolean hasValidResult() {
            return best != null;
        }

        public List<FitResult> getValidResults() {
            return results.stream()
                    .filter(r -> r.valid && r.etaMs > 0)
                    .sorted(Comparator.comparingDouble(r -> r.relativeRMSE))
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

        // Prepare data
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

        // Try each enabled formula
        if (config.isLinearEnabled()) {
            results.add(fitLinear(times, positions, finalMeanPosition, sessionStartTime));
        }

        if (config.isQuadraticEnabled()) {
            results.add(fitQuadratic(times, positions, finalMeanPosition, sessionStartTime));
        }

        if (config.isExponentialEnabled()) {
            results.add(fitExponential(times, positions, finalMeanPosition, sessionStartTime));
        }

        if (config.isPowerLawEnabled()) {
            results.add(fitPowerLaw(times, positions, finalMeanPosition, sessionStartTime));
        }

        if (config.isLogarithmicEnabled()) {
            results.add(fitLogarithmic(times, positions, finalMeanPosition, sessionStartTime));
        }

        return new MultiResult(results);
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
                return new FitResult(FormulaType.LINEAR, new double[] { 0, 0 }, 100, -1, false);
            }

            double B = (n * sumTP - sumT * sumP) / denom;
            double A = (sumP - B * sumT) / n;

            // We want decreasing position, so B should be negative in our formula P = A -
            // B*t
            // Actually we defined it as A - B*t, so B should be positive for decreasing
            B = -B; // Convert from slope to rate

            // Calculate RMSE
            double sse = 0;
            for (int i = 0; i < n; i++) {
                double predicted = A - B * times[i];
                double error = positions[i] - predicted;
                sse += error * error;
            }
            double rmse = Math.sqrt(sse / n);
            double relRMSE = (meanPos > 0) ? (rmse / meanPos) * 100 : 100;

            // Calculate ETA: when does A - B*t = 0? t = A/B
            long etaMs = -1;
            if (B > 0 && A > 0) {
                double tZeroMinutes = A / B;
                etaMs = (long) (tZeroMinutes * TIME_SCALE) - dataPoints.get(dataPoints.size() - 1).timestamp;
                if (etaMs < 0)
                    etaMs = -1;
            }

            QueueEstimatorMod.LOGGER.debug("Linear fit: A={}, B={}, RMSE%={}",
                    formatNumber(A), formatNumber(B), String.format("%.2f", relRMSE));

            return new FitResult(FormulaType.LINEAR, new double[] { A, B }, relRMSE, etaMs, true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.debug("Linear fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.LINEAR, new double[] { 0, 0 }, 100, -1, false);
        }
    }

    /**
     * Quadratic fit: P(t) = A - B*t - C*t²
     */
    private FitResult fitQuadratic(double[] times, double[] positions, double meanPos, long sessionStart) {
        try {
            int n = positions.length;

            // Use normal equations for polynomial fitting
            // Matrix form: [n, sum(t), sum(t²)] [A] [sum(P)]
            // [sum(t), sum(t²), sum(t³)] [B] = [sum(t*P)]
            // [sum(t²), sum(t³), sum(t⁴)] [C] [sum(t²*P)]

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

            // Solve 3x3 system using Cramer's rule
            double det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s2 * s3) + s2 * (s1 * s3 - s2 * s2);
            if (Math.abs(det) < 1e-10) {
                return new FitResult(FormulaType.QUADRATIC, new double[] { 0, 0, 0 }, 100, -1, false);
            }

            double A = (sp0 * (s2 * s4 - s3 * s3) - s1 * (sp1 * s4 - sp2 * s3) + s2 * (sp1 * s3 - sp2 * s2)) / det;
            double negB = (s0 * (sp1 * s4 - sp2 * s3) - sp0 * (s1 * s4 - s2 * s3) + s2 * (s1 * sp2 - s2 * sp1)) / det;
            double negC = (s0 * (s2 * sp2 - s3 * sp1) - s1 * (s1 * sp2 - s2 * sp1) + sp0 * (s1 * s3 - s2 * s2)) / det;

            double B = -negB;
            double C = -negC;

            // Calculate RMSE
            double sse = 0;
            for (int i = 0; i < n; i++) {
                double predicted = A - B * times[i] - C * times[i] * times[i];
                double error = positions[i] - predicted;
                sse += error * error;
            }
            double rmse = Math.sqrt(sse / n);
            double relRMSE = (meanPos > 0) ? (rmse / meanPos) * 100 : 100;

            // Calculate ETA: solve A - B*t - C*t² = 0 using quadratic formula
            // C*t² + B*t - A = 0 => t = (-B ± sqrt(B² + 4CA)) / (2C)
            long etaMs = -1;
            if (Math.abs(C) > 1e-10) {
                double discriminant = B * B + 4 * C * A;
                if (discriminant >= 0) {
                    double t1 = (-B + Math.sqrt(discriminant)) / (2 * C);
                    double t2 = (-B - Math.sqrt(discriminant)) / (2 * C);
                    double tZero = (t1 > 0) ? t1 : t2;
                    if (tZero > 0) {
                        etaMs = (long) (tZero * TIME_SCALE) - dataPoints.get(dataPoints.size() - 1).timestamp;
                        if (etaMs < 0)
                            etaMs = -1;
                    }
                }
            } else if (B > 0) {
                // Degenerate to linear
                double tZero = A / B;
                if (tZero > 0) {
                    etaMs = (long) (tZero * TIME_SCALE) - dataPoints.get(dataPoints.size() - 1).timestamp;
                    if (etaMs < 0)
                        etaMs = -1;
                }
            }

            QueueEstimatorMod.LOGGER.debug("Quadratic fit: A={}, B={}, C={}, RMSE%={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.2f", relRMSE));

            return new FitResult(FormulaType.QUADRATIC, new double[] { A, B, C }, relRMSE, etaMs, true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.debug("Quadratic fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.QUADRATIC, new double[] { 0, 0, 0 }, 100, -1, false);
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

            // Calculate RMSE
            double rmse = optimum.getRMS();
            double relRMSE = (meanPos > 0) ? (rmse / meanPos) * 100 : 100;

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

            QueueEstimatorMod.LOGGER.debug("Exponential fit: A={}, B={}, C={}, RMSE%={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.2f", relRMSE));

            return new FitResult(FormulaType.EXPONENTIAL, new double[] { A, B, C }, relRMSE, etaMs, true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.debug("Exponential fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.EXPONENTIAL, new double[] { 0, 0, 0 }, 100, -1, false);
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

            double rmse = optimum.getRMS();
            double relRMSE = (meanPos > 0) ? (rmse / meanPos) * 100 : 100;

            // Calculate ETA: A*(t+1)^(-B) + C = 0 => (t+1)^(-B) = -C/A
            // Only valid if C < 0 (which we don't allow) - use tangent approximation
            long etaMs = -1;
            if (B > 0 && A > 0) {
                // Use tangent at current point
                double currentT = times[n - 1];
                double currentP = positions[n - 1];
                double slope = -A * B * Math.pow(currentT + 1, -B - 1);
                if (slope < 0 && currentP > 0) {
                    double tRemaining = -currentP / slope;
                    etaMs = (long) (tRemaining * TIME_SCALE);
                }
            }

            QueueEstimatorMod.LOGGER.debug("Power law fit: A={}, B={}, C={}, RMSE%={}",
                    formatNumber(A), formatNumber(B), formatNumber(C), String.format("%.2f", relRMSE));

            return new FitResult(FormulaType.POWER_LAW, new double[] { A, B, C }, relRMSE, etaMs, true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.debug("Power law fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.POWER_LAW, new double[] { 0, 0, 0 }, 100, -1, false);
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
                return new FitResult(FormulaType.LOGARITHMIC, new double[] { 0, 0 }, 100, -1, false);
            }

            double B = -(n * sumXP - sumX * sumP) / denom; // Note the negative for our formula
            double A = (sumP + B * sumX) / n;

            // Calculate RMSE
            double sse = 0;
            for (int i = 0; i < n; i++) {
                double predicted = A - B * Math.log(times[i] + 1);
                double error = positions[i] - predicted;
                sse += error * error;
            }
            double rmse = Math.sqrt(sse / n);
            double relRMSE = (meanPos > 0) ? (rmse / meanPos) * 100 : 100;

            // Calculate ETA: A - B*ln(t+1) = 0 => ln(t+1) = A/B => t = e^(A/B) - 1
            long etaMs = -1;
            if (B > 0 && A > 0) {
                double ratio = A / B;
                if (ratio < 50) { // Prevent overflow
                    double tZero = Math.exp(ratio) - 1;
                    etaMs = (long) (tZero * TIME_SCALE) - dataPoints.get(dataPoints.size() - 1).timestamp;
                    if (etaMs < 0)
                        etaMs = -1;
                }
            }

            QueueEstimatorMod.LOGGER.debug("Logarithmic fit: A={}, B={}, RMSE%={}",
                    formatNumber(A), formatNumber(B), String.format("%.2f", relRMSE));

            return new FitResult(FormulaType.LOGARITHMIC, new double[] { A, B }, relRMSE, etaMs, true);

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.debug("Logarithmic fit failed: {}", e.getMessage());
            return new FitResult(FormulaType.LOGARITHMIC, new double[] { 0, 0 }, 100, -1, false);
        }
    }

    private static String formatNumber(double value) {
        double absValue = Math.abs(value);
        if (absValue >= 1e4 || (absValue > 0 && absValue < 1e-4)) {
            return String.format("%.3e", value);
        }
        return String.format("%.4f", value);
    }
}
