package frc.robot.subsystems.swerve.gyro;

import static edu.wpi.first.units.Units.MetersPerSecondPerSecond;
import static edu.wpi.first.units.Units.Radians;
import static edu.wpi.first.units.Units.RadiansPerSecond;

import org.littletonrobotics.junction.Logger;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.Pigeon2Configuration;
import com.ctre.phoenix6.hardware.Pigeon2;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.LinearAcceleration;
import java.util.Queue;
import frc.robot.lib.util.PhoenixUtil;
import frc.robot.subsystems.swerve.PhoenixOdometryThread;

public class GyroIOPigeon2 implements GyroIO {
    private final Pigeon2 gyro;

    private final StatusSignal<Angle> yawSignal;
    private final StatusSignal<AngularVelocity> yawVelocitySignal;

    private final StatusSignal<Angle> rollSignal;
    private final StatusSignal<AngularVelocity> rollVelocitySignal;

    private final StatusSignal<Angle> pitchSignal;
    private final StatusSignal<AngularVelocity> pitchVelocitySignal;

    private final StatusSignal<LinearAcceleration> accelerationXSignal;
    private final StatusSignal<LinearAcceleration> accelerationYSignal;

    private final Queue<Double> odometryTimestampQueue;
    private final Queue<Double> yawPositionQueue;

    public GyroIOPigeon2() {
        gyro = new Pigeon2(2, "drivetrain");
        Pigeon2Configuration config = new Pigeon2Configuration();
        config.MountPose.MountPoseYaw = 88.42582702636719;
        config.MountPose.MountPosePitch = 0.6793335676193237;
        config.MountPose.MountPoseRoll = -176.21868896484375;
        config.GyroTrim.GyroScalarZ = 0;

        PhoenixUtil.tryUntilOk(5, () -> gyro.getConfigurator().apply(config, 0.25));

        yawSignal = gyro.getYaw().clone();
        yawVelocitySignal = gyro.getAngularVelocityZWorld().clone();

        rollSignal = gyro.getRoll().clone();
        rollVelocitySignal = gyro.getAngularVelocityXWorld().clone();

        pitchSignal = gyro.getPitch().clone();
        pitchVelocitySignal = gyro.getAngularVelocityYWorld().clone();

        accelerationXSignal = gyro.getAccelerationX().clone();
        accelerationYSignal = gyro.getAccelerationY().clone();

        BaseStatusSignal.setUpdateFrequencyForAll(
                70,
                yawSignal,
                yawVelocitySignal,
                rollSignal,
                rollVelocitySignal,
                pitchSignal,
                pitchVelocitySignal,
                accelerationXSignal,
                accelerationYSignal);

        odometryTimestampQueue = PhoenixOdometryThread.getInstance().makeTimestampQueue();
        yawPositionQueue = PhoenixOdometryThread.getInstance().registerSignal(yawSignal.clone());

        gyro.optimizeBusUtilization();
    }

    @Override
    public synchronized void updateInputs(GyroIOInputs inputs) {
        BaseStatusSignal.refreshAll(
            yawSignal,
            yawVelocitySignal,
            rollSignal,
            rollVelocitySignal,
            pitchSignal,
            pitchVelocitySignal,
            accelerationXSignal,
            accelerationYSignal
        );
        inputs.isConnected = true;

        inputs.yawPosition = new Rotation2d(MathUtil.angleModulus(BaseStatusSignal.getLatencyCompensatedValue(yawSignal, yawVelocitySignal).in(Radians)));
        inputs.yawVelocityRadPerSec = yawVelocitySignal.getValue().in(RadiansPerSecond);

        inputs.odometryTimestampsSeconds = odometryTimestampQueue.stream().mapToDouble(Double::doubleValue).toArray();
        inputs.odometryYawPositions = yawPositionQueue.stream().map((Double value) -> Rotation2d.fromDegrees(value)).toArray(Rotation2d[]::new);

        odometryTimestampQueue.clear();
        yawPositionQueue.clear();
    }

    @Override
    public void resetGyro(Rotation2d yaw) {
        gyro.setYaw(yaw.getDegrees());
    }
}