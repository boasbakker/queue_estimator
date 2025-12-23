# Queue Estimator Mod

A Minecraft Fabric client-side mod that estimates your queue wait time based on the title text displayed by servers.

## Features

- **Real-time queue tracking**: Monitors the title text for "Position in queue: X" messages
- **Multi-formula curve fitting**: Uses 7 different mathematical models to predict wait times
- **Configurable via Mod Menu**: Enable/disable individual formulas
- **ETA display**: Shows estimated entry time in your local timezone (24-hour format)
- **Apache Commons Math**: Uses Levenberg-Marquardt optimization for accurate curve fitting

## How It Works

1. The mod intercepts title packets sent by the server
2. Extracts queue position from text like "Position in queue: 123"
3. Records data points (time, position) whenever the position changes
4. After enough data points, fits multiple curves to the data
5. Displays the best-fitting model with lowest RMSE% and its ETA prediction

## Mathematical Models

This mod supports 7 different curve fitting formulas. Each is derived from a physical model of how queues behave. Below are the full derivations from differential equations.

### Linear: $P(t) = A - Bt$

**Constraints**: $A > 0$, $B > 0$

**Physical Model**: People leave the queue at a constant rate.

**Differential Equation**:

$$\frac{dP}{dt} = -k \quad \text{where } k > 0$$

**Full Solution**:

$$\int dP = \int -k \, dt$$

$$P(t) = -kt + C_1$$

Initial condition: $P(0) = P_0 \Rightarrow C_1 = P_0$

$$\therefore P(t) = P_0 - kt$$

Reparameterized: $P(t) = A - Bt$ where $A = P_0$, $B = k$

**ETA Calculation**: $P(t) = 0$ when $t = \frac{A}{B}$

---

### Quadratic: $P(t) = A + Bt + Ct^2$

**Constraints**: $A > 0$ (solved analytically, no optimization constraints)

**Physical Model**: Queue processing rate changes linearly with time.

**Differential Equation**:

$$\frac{dP}{dt} = -k - \alpha t \quad \text{where } k > 0, \alpha \text{ can be } \pm$$

**Full Solution**:

$$\int dP = \int (-k - \alpha t) \, dt$$

$$P(t) = -kt - \frac{1}{2}\alpha t^2 + C_1$$

Initial condition: $P(0) = P_0 \Rightarrow C_1 = P_0$

$$\therefore P(t) = P_0 - kt - \frac{1}{2}\alpha t^2$$

Reparameterized: $P(t) = A + Bt + Ct^2$ where $A = P_0$, $B = -k$, $C = -\frac{\alpha}{2}$

**ETA Calculation**: Solve $Ct^2 + Bt + A = 0$ using quadratic formula:

$$t = \frac{-B \pm \sqrt{B^2 - 4AC}}{2C}$$

---

### Shifted Exponential: $P(t) = Ae^{-Bt} - C$

**Constraints**: $A > 0$, $B > 0$, $C \geq 0$

**Physical Model**: Dropout rate proportional to current position plus a constant server processing rate.

**Differential Equation**:

$$\frac{dP}{dt} = -\lambda P - \mu \quad \text{where } \lambda > 0, \mu > 0$$

**Full Solution**:

This is a first-order linear ODE. Using integrating factor $e^{\lambda t}$:

$$\frac{d}{dt}\left[P \cdot e^{\lambda t}\right] = -\mu \cdot e^{\lambda t}$$

$$P \cdot e^{\lambda t} = -\frac{\mu}{\lambda} e^{\lambda t} + C_1$$

$$P(t) = -\frac{\mu}{\lambda} + C_1 e^{-\lambda t}$$

Initial condition: $P(0) = P_0$

$$P_0 = -\frac{\mu}{\lambda} + C_1 \Rightarrow C_1 = P_0 + \frac{\mu}{\lambda}$$

$$\therefore P(t) = \left(P_0 + \frac{\mu}{\lambda}\right)e^{-\lambda t} - \frac{\mu}{\lambda}$$

Reparameterized: $P(t) = Ae^{-Bt} - C$ where $A = P_0 + \frac{\mu}{\lambda}$, $B = \lambda$, $C = \frac{\mu}{\lambda}$

**ETA Calculation**: $P(t) = 0$ when $Ae^{-Bt} = C$, so $t = \frac{\ln(A/C)}{B}$

---

### Power Law: $P(t) = A(t+1)^{-B} + C$

**Constraints**: $A > 0$, $B > 0$, $C \geq 0$

**Physical Model**: Dropout rate follows a power-law decay.

**Differential Equation**:

$$\frac{dP}{dt} = -k(t+1)^{-\beta-1} \quad \text{where } k > 0, \beta > 0$$

**Full Solution**:

$$\int dP = \int -k(t+1)^{-\beta-1} \, dt$$

$$P(t) = -k \cdot \frac{(t+1)^{-\beta}}{-\beta} + C_1 = \frac{k}{\beta}(t+1)^{-\beta} + C_1$$

Initial condition: $P(0) = P_0$

$$P_0 = \frac{k}{\beta} + C_1 \Rightarrow C_1 = P_0 - \frac{k}{\beta}$$

$$\therefore P(t) = \frac{k}{\beta}(t+1)^{-\beta} + \left(P_0 - \frac{k}{\beta}\right)$$

Reparameterized: $P(t) = A(t+1)^{-B} + C$ where $A = \frac{k}{\beta}$, $B = \beta$, $C = P_0 - \frac{k}{\beta}$

**ETA Calculation**: $P(t) = 0$ when $(t+1)^{-B} = -\frac{C}{A}$, so $t = \left(-\frac{A}{C}\right)^{1/B} - 1$ (requires $C < 0$)

---

### Logarithmic: $P(t) = A - B\ln(t + 1)$

**Constraints**: $A > 0$, $B > 0$

**Physical Model**: Dropout rate inversely proportional to elapsed time.

**Differential Equation**:

$$\frac{dP}{dt} = -\frac{k}{t + 1} \quad \text{where } k > 0$$

**Full Solution**:

$$\int dP = \int -\frac{k}{t+1} \, dt$$

$$P(t) = -k \ln(t+1) + C_1$$

Initial condition: $P(0) = P_0$

$$P_0 = -k \ln(1) + C_1 = C_1$$

$$\therefore P(t) = P_0 - k\ln(t+1)$$

Reparameterized: $P(t) = A - B\ln(t+1)$ where $A = P_0$, $B = k$

**ETA Calculation**: $P(t) = 0$ when $\ln(t+1) = \frac{A}{B}$, so $t = e^{A/B} - 1$

---

### Tangent: $P(t) = A\tan(B - kt) - D$

**Constraints**: $A > 0$, $0 < B < \frac{\pi}{2}$, $k > 0$, $D \geq 0$

**Physical Model**: Dropout rate includes a term proportional to position squared, creating cascading effects.

**Differential Equation**:

$$\frac{dP}{dt} = -c(1 + \alpha P^2) \quad \text{where } c > 0, \alpha > 0$$

This represents a queue where the rate of leaving depends quadratically on position—when you're far back, the combined effect of everyone ahead also leaving creates compounding behavior.

**Full Solution**:

$$\frac{dP}{1 + \alpha P^2} = -c \, dt$$

Integrate both sides:

$$\frac{1}{\sqrt{\alpha}} \arctan(\sqrt{\alpha} P) = -ct + C_1$$

Solve for $P$:

$$\arctan(\sqrt{\alpha} P) = -c\sqrt{\alpha} \, t + C_1\sqrt{\alpha}$$

$$\sqrt{\alpha} P = \tan(C_1\sqrt{\alpha} - c\sqrt{\alpha} \, t)$$

$$P(t) = \frac{1}{\sqrt{\alpha}} \tan(C_1\sqrt{\alpha} - c\sqrt{\alpha} \, t)$$

Initial condition: $P(0) = P_0$

$$P_0 = \frac{1}{\sqrt{\alpha}} \tan(C_1\sqrt{\alpha}) \Rightarrow C_1\sqrt{\alpha} = \arctan(\sqrt{\alpha} P_0)$$

Let: $A = \frac{1}{\sqrt{\alpha}}$, $B = \arctan(\sqrt{\alpha} P_0)$, $k = c\sqrt{\alpha}$

$$\therefore P(t) = A\tan(B - kt)$$

With vertical shift $D$ for better fitting: $P(t) = A\tan(B - kt) - D$

**ETA Calculation**: $P(t) = 0$ when $\tan(B - kt) = \frac{D}{A}$, so $t = \frac{B - \arctan(D/A)}{k}$

---

### Hyperbolic: $P(t) = \frac{A}{t + B} - C$

**Constraints**: $A > 0$, $B > 0$, $C \geq 0$

**Physical Model**: This is a **small-angle approximation of the tangent function**. For small angles $\theta$, $\tan(\theta) \approx \theta$, so:

$$A\tan(B - kt) \approx A(B - kt) = AB - Akt$$

After reparameterization, this becomes $\frac{A'}{t + B'}$.

More directly, this models inverse-time decay:

**Differential Equation**:

$$\frac{dP}{dt} = -\frac{A}{(t + B)^2} \quad \text{where } A > 0, B > 0$$

**Full Solution**:

$$\int dP = \int -\frac{A}{(t+B)^2} \, dt$$

$$P(t) = \frac{A}{t+B} + C_1$$

Initial condition: $P(0) = P_0$

$$P_0 = \frac{A}{B} + C_1 \Rightarrow C_1 = P_0 - \frac{A}{B}$$

$$\therefore P(t) = \frac{A}{t+B} + \left(P_0 - \frac{A}{B}\right)$$

Reparameterized: $P(t) = \frac{A}{t+B} - C$ where $C = \frac{A}{B} - P_0$ (adjusted by fitting)

**Connection to Tangent**: The hyperbolic function is the linearization of the tangent function around small angles. When the tangent fit gives small values of $(B - kt)$, the hyperbolic form provides a simpler approximation with fewer parameters.

**ETA Calculation**: $P(t) = 0$ when $\frac{A}{t+B} = C$, so $t = \frac{A}{C} - B$

---

## Choosing the Right Formula

| Queue Behavior | Best Formula | Key Characteristic |
|----------------|--------------|-------------------|
| Steady, predictable | Linear | Constant rate $\frac{dP}{dt} = -k$ |
| Accelerating/decelerating | Quadratic | Rate changes: $\frac{dP}{dt} = -k - \alpha t$ |
| Natural decay | Exponential | Position-dependent: $\frac{dP}{dt} = -\lambda P - \mu$ |
| High early dropout | Power Law | Power decay: $\frac{dP}{dt} \propto t^{-\beta-1}$ |
| Slowing over time | Logarithmic | Inverse time: $\frac{dP}{dt} = -\frac{k}{t+1}$ |
| Position compounds | Tangent | Quadratic term: $\frac{dP}{dt} = -c(1 + \alpha P^2)$ |
| Tangent simplification | Hyperbolic | Linear approx: $\frac{dP}{dt} = -\frac{A}{(t+B)^2}$ |

The mod automatically fits all enabled formulas and shows you the one with the lowest relative RMSE (root mean square error as percentage of mean position).

## Configuration

With **Mod Menu** installed, you can configure:
- Enable/disable individual formulas
- Toggle showing all results vs. only the best fit
- Set minimum data points required before fitting

## Building

```bash
./gradlew build
```

The built JAR will be in `build/libs/`.

## Requirements

- Minecraft 1.21.4
- Fabric Loader 0.16.0+
- Fabric API
- Java 21
- (Optional) Mod Menu for configuration UI

## Installation

1. Install Fabric Loader
2. Install Fabric API
3. Copy the mod JAR to your `.minecraft/mods` folder
4. (Optional) Install Mod Menu for in-game configuration

## Usage

Just join a server with a queue system that displays "Position in queue: X" via title text. The mod will automatically:

1. Show your current position
2. After gathering enough data points, show the estimated entry time with the best-fitting formula

Chat messages will appear like:
- `[Queue] Position: 150 (need 2 more data points for estimate)`
- `[Queue] Position: 145 | ETA: 14:35:22 (~12 min) | RMSE: 0.8% | P(t) = 160.5·e^(-0.023t) - 5.2`

## License

MIT License
