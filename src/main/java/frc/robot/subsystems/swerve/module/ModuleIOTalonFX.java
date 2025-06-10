package frc.robot.subsystems.swerve.module;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Fahrenheit;
import static edu.wpi.first.units.Units.RadiansPerSecond;
import static edu.wpi.first.units.Units.Rotation;
import static edu.wpi.first.units.Units.RotationsPerSecond;
import static edu.wpi.first.units.Units.Volts;

import java.util.Optional;

import org.littletonrobotics.junction.Logger;

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

import edu.wpi.first.hal.HALUtil;
import edu.wpi.first.math.filter.Debouncer;
import edu.wpi.first.math.filter.SlewRateLimiter;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.units.measure.Angle;
import edu.wpi.first.units.measure.AngularAcceleration;
import edu.wpi.first.units.measure.AngularVelocity;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.units.measure.Temperature;
import edu.wpi.first.units.measure.Voltage;
import edu.wpi.first.wpilibj.DriverStation;
import frc.robot.constants.swerve.moduleConfigs.SwerveModuleGeneralConfigBase;
import frc.robot.constants.swerve.moduleConfigs.SwerveModuleSpecificConfigBase;
import frc.robot.lib.util.RebelUtil;
import frc.robot.subsystems.swerve.Phoenix6Odometry;
import frc.robot.lib.util.PhoenixUtil;
import frc.robot.lib.util.Elastic;

public class ModuleIOTalonFX implements ModuleIO {
    private final TalonFX driveMotor;
    private final TalonFX steerMotor;
    private final CANcoder steerEncoder;

    private final StatusSignal<Angle> drivePositionStatusSignal;
    private final StatusSignal<AngularVelocity> driveVelocityStatusSignal;
    private final StatusSignal<AngularAcceleration> driveAccelerationStatusSignal;

    private final StatusSignal<Voltage> driveAppliedVolts;
    private final StatusSignal<Current> driveTorqueCurrent;
    private final StatusSignal<Temperature> driveTemperature;

    private final StatusSignal<Angle> steerPositionStatusSignal;
    private final StatusSignal<AngularVelocity> steerVelocityStatusSignal;
    private final StatusSignal<Voltage> steerAppliedVolts;
    private final StatusSignal<Current> steerTorqueCurrent;
    private final StatusSignal<Temperature> steerTemperature;
    private final StatusSignal<Angle> steerEncoderPositionStatusSignal;
    private final StatusSignal<Angle> steerEncoderAbsolutePosition;

    private final VelocityTorqueCurrentFOC driveMotorRequest = new VelocityTorqueCurrentFOC(0).withSlot(0);
    private final MotionMagicExpoTorqueCurrentFOC steerMotorRequest = new MotionMagicExpoTorqueCurrentFOC(0).withSlot(0);
    private final TorqueCurrentFOC torqueCurrentFOCRequest = new TorqueCurrentFOC(0);

    private final SlewRateLimiter driveAcelLimiter;
    private final SwerveModuleGeneralConfigBase generalConfig;
    private final int moduleID;

    private double currentDriveVelo;

    private final Debouncer driveConnectedDebouncer = new Debouncer(0.25, Debouncer.DebounceType.kBoth);
    private final Debouncer steerConnectedDebouncer = new Debouncer(0.25, Debouncer.DebounceType.kBoth);
    private final Debouncer steerEncoderConnectedDebouncer = new Debouncer(0.25, Debouncer.DebounceType.kBoth);


    private final Elastic.Notification driveConnectedDisconnectAlert = new Elastic.Notification(Elastic.Notification.NotificationLevel.ERROR,
                                            "Swerve Drive Motor Disconnected", "Drive Motor Disconnected, GOOD LUCK");

    private final Elastic.Notification steerConnectedDisconnectAlert = new Elastic.Notification(Elastic.Notification.NotificationLevel.ERROR,
                                            "Swerve Steer Motor Disconnected", "Swerve Motor Disconnected, GOOD LUCK");

    private final Elastic.Notification steerEncoderConnectedDisconnectAlert = new Elastic.Notification(Elastic.Notification.NotificationLevel.ERROR,
                                            "Swerve Encoder Disconnected", "Swerve Steer CANCODER Disconnected");

    private Rotation2d lastSteerAngleRad = new Rotation2d();
    private Rotation2d lastSteerSetpoint = new Rotation2d();

    public ModuleIOTalonFX(SwerveModuleGeneralConfigBase generalConfig, SwerveModuleSpecificConfigBase specificConfig, int moduleID) {
        this.generalConfig = generalConfig;
        this.moduleID = moduleID;

        // Drive motor
        TalonFXConfiguration driveConfig = new TalonFXConfiguration();

        // Motion magic expo TODO: DIFFRENT ACELL AND DECEL SPEEDS?! CAN BE DONE BY
        // MODNFIING THE ACCEL CONSTANT ON THE FLY
        driveConfig.Slot0.kP = generalConfig.getDriveKP();
        driveConfig.Slot0.kI = generalConfig.getDriveKI();
        driveConfig.Slot0.kD = generalConfig.getDriveKD();
        driveConfig.Slot0.kS = generalConfig.getDriveKS();
        driveConfig.Slot0.kV = generalConfig.getDriveKV();
        driveConfig.Slot0.kA = generalConfig.getDriveKA();
        driveConfig.Slot0.StaticFeedforwardSign = StaticFeedforwardSignValue.UseVelocitySign;

        driveConfig.Slot1.kP = generalConfig.getDriveKP();
        driveConfig.Slot1.kI = generalConfig.getDriveKI();
        driveConfig.Slot1.kD = 0;
        driveConfig.Slot1.kS = generalConfig.getDriveKS();
        driveConfig.Slot1.kV = generalConfig.getDriveKV();
        driveConfig.Slot1.kA = 0;
        driveConfig.Slot1.StaticFeedforwardSign = StaticFeedforwardSignValue.UseVelocitySign;

        driveConfig.MotionMagic.MotionMagicAcceleration = generalConfig.getDriveMotionMagicVelocityAccelerationMetersPerSecSec();
        driveConfig.MotionMagic.MotionMagicJerk = generalConfig.getDriveMotionMagicVelocityJerkMetersPerSecSecSec();
        driveAcelLimiter = new SlewRateLimiter(generalConfig.getDriveMotionMagicVelocityJerkMetersPerSecSecSec());
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
        driveAppliedVolts = driveMotor.getMotorVoltage().clone();
        driveTorqueCurrent = driveMotor.getTorqueCurrent().clone();
        driveTemperature = driveMotor.getDeviceTemp().clone();

        steerAppliedVolts = steerMotor.getMotorVoltage().clone();
        steerTorqueCurrent = steerMotor.getTorqueCurrent().clone();
        steerTemperature = steerMotor.getDeviceTemp().clone();

        steerEncoderAbsolutePosition = steerEncoder.getAbsolutePosition().clone();
        steerEncoderPositionStatusSignal = steerEncoder.getPosition().clone();

        drivePositionStatusSignal = driveMotor.getPosition().clone();
        driveVelocityStatusSignal = driveMotor.getVelocity().clone();
        driveAccelerationStatusSignal = driveMotor.getAcceleration().clone();

        steerPositionStatusSignal = steerMotor.getPosition().clone();
        steerVelocityStatusSignal = steerMotor.getVelocity().clone();

        BaseStatusSignal.setUpdateFrequencyForAll(
            100,
            driveAppliedVolts,
            driveTorqueCurrent,
            driveTemperature,

            steerAppliedVolts,
            steerTorqueCurrent,
            steerTemperature,
            drivePositionStatusSignal,
            driveVelocityStatusSignal,
            driveAccelerationStatusSignal
        );

        BaseStatusSignal.setUpdateFrequencyForAll(
            250, 
            steerEncoderAbsolutePosition,
            steerEncoderPositionStatusSignal,
            drivePositionStatusSignal, 

            steerPositionStatusSignal,
            steerVelocityStatusSignal
        );

        Phoenix6Odometry.getInstance().registerSignal(driveMotor, drivePositionStatusSignal);
        Phoenix6Odometry.getInstance().registerSignal(steerEncoder, steerEncoderAbsolutePosition);
        Phoenix6Odometry.getInstance().registerSignal(steerEncoder, steerEncoderPositionStatusSignal);

        Phoenix6Odometry.getInstance().registerSignal(steerMotor, steerPositionStatusSignal);
        Phoenix6Odometry.getInstance().registerSignal(steerMotor, steerVelocityStatusSignal);

        driveMotor.optimizeBusUtilization();
        steerMotor.optimizeBusUtilization();
        steerEncoder.optimizeBusUtilization();
    }

    @Override
    public void updateInputs(ModuleIOInputs inputs) {
        BaseStatusSignal.refreshAll(
            driveAppliedVolts,
            driveTorqueCurrent,
            driveTemperature,
            drivePositionStatusSignal,
            driveVelocityStatusSignal,
            driveAccelerationStatusSignal,

            steerAppliedVolts,
            steerTorqueCurrent,
            steerTemperature
        );

        inputs.driveMotorConnected = 
            driveConnectedDebouncer.calculate(
                BaseStatusSignal.refreshAll(
                    driveAppliedVolts,
                    driveTorqueCurrent,
                    driveTemperature
                ).isOK() &&
                BaseStatusSignal.isAllGood(
                    drivePositionStatusSignal,
                    driveVelocityStatusSignal
                )
            );

        inputs.steerMotorConnected = 
            steerConnectedDebouncer.calculate(
                BaseStatusSignal.refreshAll(
                    steerAppliedVolts,
                    steerTorqueCurrent,
                    steerTemperature
                ).isOK() &&
                BaseStatusSignal.isAllGood(
                    steerPositionStatusSignal,
                    steerVelocityStatusSignal
                )
            );

        inputs.steerEncoderConnected = 
            steerConnectedDebouncer.calculate(
                BaseStatusSignal.refreshAll(
                    steerEncoderAbsolutePosition,
                    steerEncoderPositionStatusSignal
                ).isOK()
            );

        double drivePosition = BaseStatusSignal
                .getLatencyCompensatedValue(drivePositionStatusSignal, driveVelocityStatusSignal).in(Rotation);

        double steerRotations = BaseStatusSignal
                .getLatencyCompensatedValue(steerPositionStatusSignal, steerVelocityStatusSignal).in(Rotation);

        inputs.timestamp = HALUtil.getFPGATime() / 1.0e6;

        inputs.drivePositionMeters = drivePosition;
        inputs.driveVelocityMetersPerSec = driveVelocityStatusSignal.getValue().in(RotationsPerSecond);

        inputs.steerPosition = new Rotation2d(
                Units.rotationsToRadians(steerRotations));
        inputs.steerVelocityRadPerSec = steerVelocityStatusSignal.getValue().in(RadiansPerSecond);

        lastSteerAngleRad = new Rotation2d(inputs.steerPosition.getRadians());

        inputs.driveCurrentDrawAmps = driveTorqueCurrent.getValue().in(Amps);
        inputs.driveAppliedVolts = driveAppliedVolts.getValue().in(Volts);
        inputs.driveTemperatureFahrenheit = driveTemperature.getValue().in(Fahrenheit);

        inputs.steerCurrentDrawAmps = steerTorqueCurrent.getValue().in(Amps);
        inputs.steerAppliedVolts = steerAppliedVolts.getValue().in(Volts);
        inputs.steerTemperatureFahrenheit = steerTemperature.getValue().in(Fahrenheit);

        Logger.recordOutput("SwerveDrive/module" + moduleID + "/driveClosedLoopReference", driveMotor.getClosedLoopReference().getValueAsDouble());
        Logger.recordOutput("SwerveDrive/module" + moduleID + "/driveClosedLoopOutput", driveMotor.getClosedLoopOutput().getValueAsDouble());

        currentDriveVelo = inputs.driveVelocityMetersPerSec;

        if (!inputs.driveMotorConnected) {
            Elastic.sendNotification(driveConnectedDisconnectAlert.withDisplayMilliseconds(10000));
            DriverStation.reportError("Swerve Drive Motor Disconnected", false);
        }

        if (!inputs.steerMotorConnected) {
            Elastic.sendNotification(steerConnectedDisconnectAlert.withDisplayMilliseconds(10000));
            DriverStation.reportError("Swerve Steer Motor Disconnected", false);
        }
        if (!inputs.steerEncoderConnected) {
            Elastic.sendNotification(steerEncoderConnectedDisconnectAlert.withDisplayMilliseconds(10000));
            DriverStation.reportError("Steer Encoder Disconnected", false);
        }
    }

    @Override
    public void setState(SwerveModuleState state) {
        driveMotor.setControl(driveMotorRequest.withVelocity(
                RebelUtil.constrain(
                    state.speedMetersPerSecond,
                    -generalConfig.getDriveMaxVelocityMetersPerSec(),
                    generalConfig.getDriveMaxVelocityMetersPerSec()
                ) * state.angle.minus(lastSteerAngleRad).getCos()
            )
        );
        
        steerMotor.setControl(
            steerMotorRequest.withPosition(
                state.angle.getRotations()
            )
        );

        lastSteerSetpoint = state.angle;
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