package frc.robot.constants.swerve.moduleConfigs.comp;

import frc.robot.constants.swerve.moduleConfigs.SwerveModuleSpecificConfigBase;

public class SwerveModuleSpecificBRConfigComp extends SwerveModuleSpecificConfigBase {

    public static SwerveModuleSpecificBRConfigComp instance = null;
    public static SwerveModuleSpecificBRConfigComp getInstance() {
        if (instance == null) {
            instance = new SwerveModuleSpecificBRConfigComp();
        }
        return instance;
    }

    private SwerveModuleSpecificBRConfigComp() {}

    @Override
    public int getDriveCanId() {
        return 11;
    }

    @Override
    public int getSteerCanId() {
        return 9;
    }

    @Override
    public boolean getIsDriveInverted() {
        return false;
    }

    @Override
    public boolean getIsSteerInverted() {
        return true;
    }

    @Override
    public boolean isDriveNeutralModeBrake() {
        return true;
    }

    @Override
    public boolean isSteerNeutralModeBrake() {
        return true;
    }

    @Override
    public int getCancoderCanId() {
        return 10;
    }

    @Override
    public double getCancoderOffsetRotations() {
        return 0.0488;
    }
}
