package frc.robot.subsystems.swerve.module;

import java.util.Optional;

import org.littletonrobotics.junction.AutoLog;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;

public interface ModuleIO {
    @AutoLog
    class ModuleIOInputs {
        public double timestamp = 0;

        public double drivePositionMeters = 0;
        public double driveVelocityMetersPerSec = 0;

        public Rotation2d steerPosition = new Rotation2d();
        public double steerVelocityRadPerSec = 0;

        public double driveCurrentDrawAmps = 0;
        public double driveAppliedVolts = 0;
        public double driveTemperatureFahrenheit = 0;

        public double steerCurrentDrawAmps = 0;
        public double steerAppliedVolts = 0;
        public double steerTemperatureFahrenheit = 0;

        public boolean driveMotorConnected = true;
        public boolean steerMotorConnected = true;
        public boolean steerEncoderConnected = true;
    }

    public default void updateInputs(ModuleIOInputs inputs) {}
    public default void setState(SwerveModuleState state) {}
    public default void setDriveVoltage(double baseUnitMagnitude) {}
}
