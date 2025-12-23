# Queue Estimator Mod

A Minecraft Fabric client-side mod that estimates your queue wait time based on the title text displayed by servers.

## Features

- **Real-time queue tracking**: Monitors the title text for "Position in queue: X" messages
- **Exponential decay curve fitting**: Uses the model P(t) = A × e^(-Bt) - C to predict wait times
- **ETA display**: Shows estimated entry time in your local timezone (24-hour format)
- **Apache Commons Math**: Uses Levenberg-Marquardt optimization for accurate curve fitting

## How It Works

1. The mod intercepts title packets sent by the server
2. Extracts queue position from text like "Position in queue: 123"
3. Records data points (time, position) whenever the position changes
4. After 3+ data points, fits an exponential decay curve to the data
5. Calculates when P(t) = 0 (queue complete) and displays the estimated time

## Model

The queue position is modeled as:

```
P(t) = A × e^(-Bt) - C
```

Where:
- **A**: Initial amplitude (roughly initial queue position)
- **B**: Decay rate (how fast the queue moves)
- **C**: Offset constant

To find when you'll enter (P(t) = 0):
```
t = ln(A/C) / B
```

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

## Installation

1. Install Fabric Loader
2. Install Fabric API
3. Copy the mod JAR to your `.minecraft/mods` folder

## Usage

Just join a server with a queue system that displays "Position in queue: X" via title text. The mod will automatically:

1. Show your current position
2. After gathering enough data points, show the estimated entry time

Chat messages will appear like:
- `[Queue] Position: 150 (need 2 more data points for estimate)`
- `[Queue] Position: 145 | ETA: 14:35:22 (~12 min) | Fit: P(t) = 160.5*e^(-0.000023*t) - 5.2`

## License

MIT License
