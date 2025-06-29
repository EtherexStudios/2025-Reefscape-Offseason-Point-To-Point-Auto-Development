package frc.robot.subsystems.swerve.module;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Fahrenheit;
import static edu.wpi.first.units.Units.RadiansPerSecond;
import static edu.wpi.first.units.Units.Radians;
import static edu.wpi.first.units.Units.Rotation;
import static edu.wpi.first.units.Units.RotationsPerSecond;

import java.util.Queue;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.CANcoderConfiguration;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.controls.MotionMagicExpoTorqueCurrentFOC;
import com.ctre.phoenix6.controls.TorqueCurrentFOC;
import com.ctre.phoenix6.controls.VelocityTorqueCurrentFOC;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import com.ctre.phoenix6.signals.StaticFeedforwardSignValue;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Temperature;
import edu.wpi.first.wpilibj.Timer;
import frc.robot.constants.swerve.moduleConfigs.SwerveModuleGeneralConfigBase;
import frc.robot.constants.swerve.moduleConfigs.SwerveModuleSpecificConfigBase;
import frc.robot.lib.util.RebelUtil;
import frc.robot.subsystems.swerve.PhoenixOdometryThread;
import frc.robot.lib.util.PhoenixUtil;
import frc.robot.subsystems.swerve.SwerveDrive;

public class ModuleIOTalonFX implements ModuleIO {
    private final TalonFX driveMotor;
    private final TalonFX steerMotor;
    private final CANcoder steerEncoder;

    private final Queue<Double> timestampQueue;
    private final Queue<Double> drivePositionQueue;
    private final Queue<Double>  steerPositionQueue;

    private final StatusSignal<Angle> drivePositionStatusSignal;
    private final StatusSignal<AngularVelocity> driveVelocityStatusSignal;

    private final StatusSignal<Angle> steerPositionStatusSignal;
    private final StatusSignal<AngularVelocity> steerVelocityStatusSignal;

    private final StatusSignal<Angle> steerEncoderPositionStatusSignal;
    private final StatusSignal<Angle> steerEncoderAbsolutePosition;

    private final StatusSignal<Current> driveTorqueCurrent;
    private final StatusSignal<Temperature> driveTemperature;

    private final StatusSignal<Current> steerTorqueCurrent;
    private final StatusSignal<Temperature> steerTemperature;

    private final VelocityTorqueCurrentFOC driveMotorRequest = new VelocityTorqueCurrentFOC(0).withSlot(0);
    private final MotionMagicExpoTorqueCurrentFOC steerMotorRequest = new MotionMagicExpoTorqueCurrentFOC(0).withSlot(0);
    private final TorqueCurrentFOC torqueCurrentFOCRequest = new TorqueCurrentFOC(0);

    private final SwerveModuleGeneralConfigBase generalConfig;

    private Rotation2d lastSteerAngleRad = new Rotation2d();
    private SwerveModuleState lastRequestedState = new SwerveModuleState();
    private double lastRequestedStateTime = Timer.getFPGATimestamp();

    public ModuleIOTalonFX(SwerveModuleGeneralConfigBase generalConfig, SwerveModuleSpecificConfigBase specificConfig) {
        this.generalConfig = generalConfig;

        // Drive motor
        TalonFXConfiguration driveConfig = new TalonFXConfiguration();

        driveConfig.Slot0.kP = generalConfig.getDriveKP();
        driveConfig.Slot0.kI = generalConfig.getDriveKI();
        driveConfig.Slot0.kD = generalConfig.getDriveKD();
        driveConfig.Slot0.kS = generalConfig.getDriveKS();
        driveConfig.Slot0.kV = generalConfig.getDriveKV();
        driveConfig.Slot0.kA = generalConfig.getDriveKA();
        driveConfig.Slot0.StaticFeedforwardSign = StaticFeedforwardSignValue.UseVelocitySign;

        driveConfig.MotionMagic.MotionMagicAcceleration = generalConfig.getDriveMotionMagicVelocityAccelerationMetersPerSecSec();
        driveConfig.MotionMagic.MotionMagicJerk = generalConfig.getDriveMotionMagicVelocityJerkMetersPerSecSecSec();

        // Cancoder + encoder
        driveConfig.ClosedLoopGeneral.ContinuousWrap = false;
        driveConfig.Feedback.SensorToMechanismRatio = 
            generalConfig.getDriveMotorToOutputShaftRatio() /
            (generalConfig.getDriveWheelRadiusMeters() * 2 * Math.PI);

        driveConfig.MotorOutput.NeutralMode = 
            generalConfig.getIsDriveNeutralModeBrake() ? 
                NeutralModeValue.Brake : 
                NeutralModeValue.Coast;

        driveConfig.MotorOutput.Inverted = 
            specificConfig.getIsDriveInverted() ?
                InvertedValue.Clockwise_Positive :
                InvertedValue.CounterClockwise_Positive;

        // Current and torque limiting
        driveConfig.CurrentLimits.SupplyCurrentLimitEnable = true;
        driveConfig.CurrentLimits.SupplyCurrentLimit = generalConfig.getDriveSupplyCurrentLimit();
        driveConfig.CurrentLimits.SupplyCurrentLowerLimit = generalConfig.getDriveSupplyCurrentLimit();
        driveConfig.CurrentLimits.SupplyCurrentLowerTime = generalConfig.getDriveSupplyCurrentLimitLowerTime();

        driveConfig.CurrentLimits.StatorCurrentLimitEnable = true;
        driveConfig.CurrentLimits.StatorCurrentLimit = generalConfig.getDriveStatorCurrentLimit();

        driveConfig.TorqueCurrent.PeakForwardTorqueCurrent = generalConfig.getDrivePeakForwardTorqueCurrent();
        driveConfig.TorqueCurrent.PeakReverseTorqueCurrent = generalConfig.getDrivePeakReverseTorqueCurrent();

        driveConfig.FutureProofConfigs = true;
        
        driveMotor = new TalonFX(specificConfig.getDriveCanId(), generalConfig.getCanBusName());
        PhoenixUtil.tryUntilOk(5, () -> driveMotor.getConfigurator().apply(driveConfig, 0.25));

        // ABS encoder
        CANcoderConfiguration encoderConfig = new CANcoderConfiguration();
        encoderConfig.MagnetSensor.AbsoluteSensorDiscontinuityPoint = generalConfig.getCancoderAbsoluteSensorDiscontinuityPoint();
        encoderConfig.MagnetSensor.SensorDirection = generalConfig.getCancoderSensorDirection();
        encoderConfig.MagnetSensor.withMagnetOffset(specificConfig.getCancoderOffsetRotations());

        encoderConfig.FutureProofConfigs = true;

        steerEncoder = new CANcoder(specificConfig.getCancoderCanId(), generalConfig.getCanBusName());
        PhoenixUtil.tryUntilOk(5, () -> steerEncoder.getConfigurator().apply(encoderConfig, 0.25));

        // Steer motor
        TalonFXConfiguration steerConfig = new TalonFXConfiguration();

        // Motion magic expo
        steerConfig.Slot0.kP = generalConfig.getSteerKP();
        steerConfig.Slot0.kI = generalConfig.getSteerKI();
        steerConfig.Slot0.kD = generalConfig.getSteerKD();
        steerConfig.Slot0.kS = generalConfig.getSteerKS();
        steerConfig.Slot0.kV = generalConfig.getSteerKV();
        steerConfig.Slot0.kA = generalConfig.getSteerKA();
        steerConfig.Slot0.StaticFeedforwardSign = StaticFeedforwardSignValue.UseVelocitySign;

        steerConfig.MotionMagic.MotionMagicExpo_kA = generalConfig.getSteerMotionMagicExpoKA();
        steerConfig.MotionMagic.MotionMagicExpo_kV = generalConfig.getSteerMotionMagicExpoKV();
        steerConfig.MotionMagic.MotionMagicCruiseVelocity = generalConfig.getSteerMotionMagicCruiseVelocityRotationsPerSec();

        steerConfig.MotorOutput.NeutralMode = 
            generalConfig.getIsSteerNeutralModeBrake() ? 
                NeutralModeValue.Brake : 
                NeutralModeValue.Coast;

        steerConfig.MotorOutput.Inverted = 
            specificConfig.getIsSteerInverted() ?
                InvertedValue.Clockwise_Positive :
                InvertedValue.CounterClockwise_Positive;
                
        // Cancoder + encoder
        steerConfig.ClosedLoopGeneral.ContinuousWrap = true;
        steerConfig.Feedback.FeedbackRemoteSensorID = specificConfig.getCancoderCanId();
        steerConfig.Feedback.FeedbackSensorSource = generalConfig.getSteerCancoderFeedbackSensorSource();
        steerConfig.Feedback.SensorToMechanismRatio = 1;
        steerConfig.Feedback.RotorToSensorRatio = generalConfig.getSteerRotorToSensorRatio();

        // current and torque limiting
        steerConfig.CurrentLimits.SupplyCurrentLimitEnable = true;
        steerConfig.CurrentLimits.SupplyCurrentLimit = generalConfig.getSteerSupplyCurrentLimit();
        steerConfig.CurrentLimits.SupplyCurrentLowerLimit = generalConfig.getSteerSupplyCurrentLimitLowerLimit();
        steerConfig.CurrentLimits.SupplyCurrentLowerTime = generalConfig.getSteerSupplyCurrentLimitLowerTime();

        steerConfig.CurrentLimits.StatorCurrentLimitEnable = true;
        steerConfig.CurrentLimits.StatorCurrentLimit = generalConfig.getSteerStatorCurrentLimit();

        steerConfig.TorqueCurrent.PeakForwardTorqueCurrent = generalConfig.getSteerPeakForwardTorqueCurrent();
        steerConfig.TorqueCurrent.PeakReverseTorqueCurrent = generalConfig.getSteerPeakReverseTorqueCurrent();

        steerConfig.FutureProofConfigs = true;

        steerMotor = new TalonFX(specificConfig.getSteerCanId(), generalConfig.getCanBusName());
        PhoenixUtil.tryUntilOk(5, () -> steerMotor.getConfigurator().apply(steerConfig, 0.25));

        // status signals
        driveTorqueCurrent = driveMotor.getTorqueCurrent().clone();
        driveTemperature = driveMotor.getDeviceTemp().clone();

        steerTorqueCurrent = steerMotor.getTorqueCurrent().clone();
        steerTemperature = steerMotor.getDeviceTemp().clone();

        steerEncoderAbsolutePosition = steerEncoder.getAbsolutePosition().clone();
        steerEncoderPositionStatusSignal = steerEncoder.getPosition().clone();

        drivePositionStatusSignal = driveMotor.getPosition().clone();
        driveVelocityStatusSignal = driveMotor.getVelocity().clone();

        steerPositionStatusSignal = steerMotor.getPosition().clone();
        steerVelocityStatusSignal = steerMotor.getVelocity().clone();

        timestampQueue = PhoenixOdometryThread.getInstance().makeTimestampQueue();
        drivePositionQueue = PhoenixOdometryThread.getInstance().registerSignal(drivePositionStatusSignal.clone());
        steerPositionQueue = PhoenixOdometryThread.getInstance().registerSignal(steerPositionStatusSignal.clone());

        BaseStatusSignal.setUpdateFrequencyForAll(
            100,
            driveTorqueCurrent,
            driveTemperature,

            steerTorqueCurrent,
            steerTemperature,

            steerEncoderAbsolutePosition,
            steerEncoderPositionStatusSignal
        );

        BaseStatusSignal.setUpdateFrequencyForAll(
            SwerveDrive.ODOMETRY_FREQUENCY, 
            drivePositionStatusSignal, 
            steerPositionStatusSignal,

            driveVelocityStatusSignal,
            steerVelocityStatusSignal
        );

        driveMotor.optimizeBusUtilization();
        steerMotor.optimizeBusUtilization();
        steerEncoder.optimizeBusUtilization();
    }

    @Override
    public void updateInputs(ModuleIOInputs inputs) {
        BaseStatusSignal.refreshAll(
            driveTorqueCurrent,
            driveTemperature,

            steerTorqueCurrent,
            steerTemperature,

            steerEncoderAbsolutePosition,
            steerEncoderPositionStatusSignal
        );

        inputs.drivePositionMeters = BaseStatusSignal.getLatencyCompensatedValue(drivePositionStatusSignal, driveVelocityStatusSignal).in(Rotation);
        inputs.driveVelocityMetersPerSec = driveVelocityStatusSignal.getValue().in(RotationsPerSecond);

        inputs.steerPosition = new Rotation2d(BaseStatusSignal.getLatencyCompensatedValue(steerPositionStatusSignal, steerVelocityStatusSignal).in(Radians));
        inputs.steerVelocityRadPerSec = steerVelocityStatusSignal.getValue().in(RadiansPerSecond);

        inputs.steerEncoderAbsolutePosition = new Rotation2d(steerEncoderAbsolutePosition.getValue().in(Radians));
        inputs.steerEncoderPosition = new Rotation2d(steerEncoderPositionStatusSignal.getValue().in(Radians));

        inputs.driveTorqueCurrent = driveTorqueCurrent.getValue().in(Amps);
        inputs.driveTemperatureFahrenheit = driveTemperature.getValue().in(Fahrenheit);

        inputs.steerTorqueCurrent = steerTorqueCurrent.getValue().in(Amps);
        inputs.steerTemperatureFahrenheit = steerTemperature.getValue().in(Fahrenheit);

        inputs.odometryTimestampsSeconds = timestampQueue.stream().mapToDouble(Double::doubleValue).toArray();
        inputs.odometryDrivePositionsMeters = drivePositionQueue.stream().mapToDouble(Double::doubleValue).toArray();
        inputs.odometrySteerPositions = steerPositionQueue.stream().map((Double value) -> Rotation2d.fromRotations(value)).toArray(Rotation2d[]::new);

        timestampQueue.clear();
        drivePositionQueue.clear();
        steerPositionQueue.clear();

        lastSteerAngleRad = new Rotation2d(inputs.steerPosition.getRadians());
    }

    @Override
    public void setState(SwerveModuleState state) {
        driveMotor.setControl(driveMotorRequest.withVelocity(
                RebelUtil.constrain(
                    state.speedMetersPerSecond,
                    -generalConfig.getDriveMaxVelocityMetersPerSec(),
                    generalConfig.getDriveMaxVelocityMetersPerSec()
                ) * state.angle.minus(lastSteerAngleRad).getCos()
            ).withAcceleration((state.speedMetersPerSecond - lastRequestedState.speedMetersPerSecond) / (Timer.getFPGATimestamp() - lastRequestedStateTime))
        );
        
        steerMotor.setControl(
            steerMotorRequest.withPosition(
                state.angle.getRotations()
            )
        );

        lastRequestedState = state;
        lastRequestedStateTime = Timer.getFPGATimestamp();
    }

    @Override
    public void setSteerTorqueCurrentFOC(double torqueCurrentFOC, double driveVelocityMetersPerSec) {
        // Set steer motor with torque FOC, optionally using drive velocity for feedforward if needed
        steerMotor.setControl(
            torqueCurrentFOCRequest.withOutput(torqueCurrentFOC)
        );

        driveMotor.setControl(driveMotorRequest.withVelocity(
                RebelUtil.constrain(
                    driveVelocityMetersPerSec,
                    -generalConfig.getDriveMaxVelocityMetersPerSec(),
                    generalConfig.getDriveMaxVelocityMetersPerSec()
                )
            )
        );
    }

    @Override
    public void setDriveTorqueCurrentFOC(double torqueCurrentFOC, Rotation2d steerAngle) {
        // Set drive motor with torque FOC, optionally using steer angle for feedforward if needed
        driveMotor.setControl(
            torqueCurrentFOCRequest.withOutput(torqueCurrentFOC)
        );

        steerMotor.setControl(
            steerMotorRequest.withPosition(
                steerAngle.getRotations()
            )
        );
    }
}