package frc.robot.subsystems.swerve.module;

import org.littletonrobotics.junction.AutoLog;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;

public interface ModuleIO {
    @AutoLog
    class ModuleIOInputs {
        public double fpgaTimestampSeconds = 0;

        public double drivePositionMeters = 0;
        public double driveVelocityMetersPerSec = 0;

        public Rotation2d steerPosition = new Rotation2d();
        public double steerVelocityRadPerSec = 0;

        public Rotation2d steerEncoderAbsolutePosition = new Rotation2d();
        public Rotation2d steerEncoderPosition = new Rotation2d();

        public double driveTorqueCurrent = 0;
        public double driveTemperatureFahrenheit = 0;

        public double steerTorqueCurrent = 0;
        public double steerTemperatureFahrenheit = 0;
    }

    public default void updateInputs(ModuleIOInputs inputs) {}

    public default void setState(SwerveModuleState state) {}
    public default void setSteerTorqueCurrentFOC(double torqueCurrentFOC, double driveVelocityMetersPerSec) {}
    public default void setDriveTorqueCurrentFOC(double torqueCurrentFOC, Rotation2d steerAngle) {}
}
