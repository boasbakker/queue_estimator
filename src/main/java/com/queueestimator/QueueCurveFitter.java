package com.queueestimator;

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.fitting.leastsquares.ParameterValidator;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import java.util.List;

/**
 * Fits queue position data to the model: P(t) = A * e^(-B*t) - C
 * Uses Levenberg-Marquardt optimization from Apache Commons Math.
 */
public class QueueCurveFitter {
    
    private final List<QueueDataTracker.DataPoint> dataPoints;
    
    // Time scale factor to normalize time values (helps with numerical stability)
    // Convert milliseconds to minutes for better numerical behavior
    private static final double TIME_SCALE = 60000.0; // 1 minute in ms
    
    /**
     * Result of curve fitting including parameters and quality metrics
     */
    public static class FitResult {
        public final double A;
        public final double B;
        public final double C;
        public final double relativeRMSE; // RMSE as percentage of mean position
        
        public FitResult(double A, double B, double C, double relativeRMSE) {
            this.A = A;
            this.B = B;
            this.C = C;
            this.relativeRMSE = relativeRMSE;
        }
    }
    
    public QueueCurveFitter(List<QueueDataTracker.DataPoint> dataPoints) {
        this.dataPoints = dataPoints;
    }
    
    /**
     * Fit the data to P(t) = A * e^(-B*t) - C
     * @return FitResult with parameters and relative RMSE, or null if fitting fails
     */
    public FitResult fit() {
        if (dataPoints.size() < 3) {
            QueueEstimatorMod.LOGGER.warn("Not enough data points for fitting: {}", dataPoints.size());
            return null;
        }
        
        try {
            // Prepare observed data - normalize time to start from 0
            final int n = dataPoints.size();
            final double[] times = new double[n];     // t values (scaled, relative to first point)
            final double[] positions = new double[n]; // P(t) observed values
            
            long t0 = dataPoints.get(0).timestamp;
            for (int i = 0; i < n; i++) {
                times[i] = (dataPoints.get(i).timestamp - t0) / TIME_SCALE; // Convert to minutes from start
                positions[i] = dataPoints.get(i).position;
            }
            
            // Initial parameter guesses based on data
            double[] initialGuess = guessInitialParameters(times, positions);
            
            // Parameter validator to keep values in reasonable ranges
            ParameterValidator validator = params -> {
                double A = params.getEntry(0);
                double B = params.getEntry(1);
                double C = params.getEntry(2);
                
                // Ensure A is positive
                A = Math.max(1.0, A);
                // Ensure B is positive but not too large (prevents numerical issues)
                B = Math.max(1e-6, Math.min(B, 10.0));
                // Ensure C is non-negative
                C = Math.max(0.0, C);
                
                return new ArrayRealVector(new double[] { A, B, C });
            };
            
            // Define the model function and its Jacobian
            MultivariateJacobianFunction model = point -> {
                double A = point.getEntry(0);
                double B = point.getEntry(1);
                double C = point.getEntry(2);
                
                RealVector value = new ArrayRealVector(n);
                RealMatrix jacobian = new Array2DRowRealMatrix(n, 3);
                
                for (int i = 0; i < n; i++) {
                    double t = times[i];
                    double expTerm = Math.exp(-B * t);
                    
                    // Clamp expTerm to prevent numerical issues
                    if (Double.isNaN(expTerm) || Double.isInfinite(expTerm)) {
                        expTerm = (B * t > 0) ? 0.0 : 1.0;
                    }
                    
                    // Model: P(t) = A * e^(-B*t) - C
                    double modelValue = A * expTerm - C;
                    value.setEntry(i, modelValue);
                    
                    // Jacobian:
                    // dP/dA = e^(-B*t)
                    // dP/dB = -A * t * e^(-B*t)
                    // dP/dC = -1
                    jacobian.setEntry(i, 0, expTerm);           // dP/dA
                    jacobian.setEntry(i, 1, -A * t * expTerm);  // dP/dB
                    jacobian.setEntry(i, 2, -1.0);              // dP/dC
                }
                
                return new Pair<>(value, jacobian);
            };
            
            // Target values (observed positions)
            double[] target = positions;
            
            // Build the least squares problem with relaxed convergence criteria
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                .start(initialGuess)
                .model(model)
                .target(target)
                .parameterValidator(validator)
                .lazyEvaluation(false)
                .maxEvaluations(5000)
                .maxIterations(2000)
                .build();
            
            // Solve using Levenberg-Marquardt with relaxed tolerances
            LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer()
                .withCostRelativeTolerance(1e-6)
                .withParameterRelativeTolerance(1e-6)
                .withOrthoTolerance(1e-6)
                .withRankingThreshold(1e-10);
            
            LeastSquaresOptimizer.Optimum optimum = optimizer.optimize(problem);
            
            double[] result = optimum.getPoint().toArray();
            
            // Convert B back to per-millisecond rate
            double A = result[0];
            double B = result[1] / TIME_SCALE; // Convert back from per-minute to per-ms
            double C = result[2];
            
            // Calculate relative RMSE (RMSE as percentage of mean position)
            double rms = optimum.getRMS();
            double meanPosition = 0;
            for (double pos : positions) {
                meanPosition += pos;
            }
            meanPosition /= n;
            double relativeRMSE = (meanPosition > 0) ? (rms / meanPosition) * 100.0 : 0.0;
            
            QueueEstimatorMod.LOGGER.info("Curve fit complete: A={}, B={}, C={}, RMS={}, relRMSE={}%, evaluations={}", 
                formatNumber(A), formatNumber(B), formatNumber(C), rms, String.format("%.2f", relativeRMSE), optimum.getEvaluations());
            
            return new FitResult(A, B, C, relativeRMSE);
            
        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.error("Curve fitting failed", e);
            // Fall back to simple linear estimation
            return fallbackLinearEstimate();
        }
    }
    
    /**
     * Format a number, using scientific notation if above 1e4 or below 1e-4
     */
    private static String formatNumber(double value) {
        double absValue = Math.abs(value);
        if (absValue >= 1e4 || (absValue > 0 && absValue < 1e-4)) {
            return String.format("%.4e", value);
        }
        return String.format("%.6f", value);
    }
    
    /**
     * Simple linear fallback when exponential fitting fails.
     * Uses linear regression to estimate when position reaches 0.
     */
    private FitResult fallbackLinearEstimate() {
        if (dataPoints.size() < 2) {
            return null;
        }
        
        try {
            // Simple linear model: P(t) = P0 - rate * t
            // Convert to exponential form: A = P0, B ≈ rate/P0, C = 0
            long t0 = dataPoints.get(0).timestamp;
            double firstPos = dataPoints.get(0).position;
            double lastPos = dataPoints.get(dataPoints.size() - 1).position;
            double deltaT = (dataPoints.get(dataPoints.size() - 1).timestamp - t0);
            
            if (deltaT <= 0 || firstPos <= lastPos) {
                return null; // No progress or invalid data
            }
            
            double rate = (firstPos - lastPos) / deltaT; // positions per ms
            
            // Approximate as exponential: A*e^(-B*t) - C where B ≈ rate/firstPos, C = 0
            double A = firstPos;
            double B = rate / firstPos;
            double C = 0;
            
            // Estimate relative RMSE for linear fallback (rough approximation)
            double meanPos = (firstPos + lastPos) / 2.0;
            double relativeRMSE = 10.0; // Default to 10% for fallback
            
            QueueEstimatorMod.LOGGER.info("Using linear fallback: A={}, B={}, C={}", formatNumber(A), formatNumber(B), formatNumber(C));
            
            return new FitResult(A, B, C, relativeRMSE);
        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.error("Linear fallback also failed", e);
            return null;
        }
    }
    
    /**
     * Generate initial parameter guesses based on the data
     */
    private double[] guessInitialParameters(double[] times, double[] positions) {
        int n = positions.length;
        
        // Initial guess strategy:
        // - A should be roughly the initial position (first data point)
        // - C should be small but positive (the queue never goes negative)
        // - B controls decay rate, estimate from the data slope
        
        double firstPos = positions[0];
        double lastPos = positions[n - 1];
        double deltaT = times[n - 1] - times[0];
        
        // Handle edge case where deltaT is 0 or positions are constant
        if (deltaT <= 0.001 || Math.abs(firstPos - lastPos) < 0.001) {
            // Not enough time passed or no change, use conservative defaults
            return new double[] { Math.max(firstPos, 1.0), 0.01, 0.0 };
        }
        
        // Estimate A as initial position
        double A = firstPos;
        
        // For C, estimate based on whether queue seems to asymptote
        // If queue is steadily decreasing, C might be 0
        // Start with C = 0 for simplicity
        double C = 0.0;
        
        // Estimate B from average decay rate
        // Simple approach: B ≈ ln(firstPos / lastPos) / deltaT
        // This assumes P(t) ≈ A * e^(-Bt) with C=0
        double B;
        if (lastPos > 0 && firstPos > lastPos) {
            B = Math.log(firstPos / lastPos) / deltaT;
        } else if (firstPos > lastPos) {
            // lastPos is 0 or negative, estimate based on linear decay
            double rate = (firstPos - lastPos) / deltaT;
            B = rate / firstPos;
        } else {
            // Queue isn't decreasing, use small default
            B = 0.001;
        }
        
        // Ensure B is in reasonable range (0.001 to 2.0 per minute)
        B = Math.max(0.001, Math.min(B, 2.0));
        
        QueueEstimatorMod.LOGGER.debug("Initial guess: A={}, B={}, C={} (deltaT={}min, firstPos={}, lastPos={})", 
            A, B, C, deltaT, firstPos, lastPos);
        
        return new double[] { A, B, C };
    }
}
