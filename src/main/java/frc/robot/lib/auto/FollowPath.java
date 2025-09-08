package frc.robot.lib.auto;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.Pair;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.lib.auto.Path.PathElement;
import frc.robot.lib.auto.Path.PathElementConstraint;
import frc.robot.lib.auto.Path.RotationTarget;
import frc.robot.lib.auto.Path.RotationTargetConstraint;
import frc.robot.lib.auto.Path.TranslationTarget;
import frc.robot.lib.auto.Path.TranslationTargetConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.littletonrobotics.junction.Logger;

public class FollowPath extends Command {
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(FollowPath.class.getName());

    private static PIDController translationController = null;
    private static PIDController rotationController = null;

    public static PIDController getTranslationController() {
        if (translationController == null) {
            throw new IllegalStateException("Translation controller has not been set");
        }
        return createPIDControllerCopy(translationController);
    }
    public static PIDController getRotationController() {
        if (rotationController == null) {
            throw new IllegalStateException("Rotation controller has not been set");
        }
        return createPIDControllerCopy(rotationController);
    }
    public static void setTranslationController(PIDController translationController) {
        if (translationController == null) {
            throw new IllegalArgumentException("Translation controller must not be null");
        }
        FollowPath.translationController = createPIDControllerCopy(translationController);
    }
    public static void setRotationController(PIDController rotationController) {
        if (rotationController == null) {
            throw new IllegalArgumentException("Rotation controller must not be null");
        }
        FollowPath.rotationController = createPIDControllerCopy(rotationController);
    }

    private static PIDController createPIDControllerCopy(PIDController source) {
        if (source == null) {
            throw new IllegalArgumentException("Cannot create copy of null PIDController");
        }
        return new PIDController(source.getP(), source.getI(), source.getD());
    }

    private void configureControllers() {
        translationController.setTolerance(path.getEndTranslationToleranceMeters());
        rotationController.setTolerance(Math.toRadians(path.getEndRotationToleranceDeg()));
        rotationController.enableContinuousInput(-Math.PI, Math.PI);
    }

    private final Path path;
    private final Supplier<Pose2d> poseSupplier;
    private final Supplier<ChassisSpeeds> robotRelativeSpeedsSupplier;
    private final Consumer<ChassisSpeeds> robotRelativeSpeedsConsumer;
    private final Supplier<Boolean> shouldFlipPathSupplier;
    private final Consumer<Pose2d> poseResetConsumer;

    private int rotationElementIndex = 0;
    private int translationElementIndex = 0;
    private ChassisSpeeds lastSpeeds = new ChassisSpeeds();
    private double lastTimestamp = 0;
    private Pose2d pathInitStartPose = new Pose2d();
    private double previousRotationElementTargetRad = 0;   
    private int previousRotationElementIndex = 0;
    private Rotation2d currentRotationTargetRad = new Rotation2d();
    private double currentRotationTargetInitRad = 0;
    private List<Pair<PathElement, PathElementConstraint>> pathElementsWithConstraints = new ArrayList<>();

    private int logCounter = 0;
    private ArrayList<Translation2d> robotTranslations = new ArrayList<>();
    private double cachedRemainingDistance = 0.0;

    public FollowPath(
        Path path, 
        SubsystemBase driveSubsystem, 
        Supplier<Pose2d> poseSupplier, 
        Supplier<ChassisSpeeds> robotRelativeSpeedsSupplier,
        Consumer<ChassisSpeeds> robotRelativeSpeedsConsumer,
        Supplier<Boolean> shouldFlipPathSupplier,
        Consumer<Pose2d> poseResetConsumer,
        PIDController translationController, 
        PIDController rotationController
    ) {
        if (translationController == null || rotationController == null) {
            throw new IllegalArgumentException("Translation and rotation controllers must be provided and must not be null or must be set before calling FollowPath");
        }

        this.path = path.copy();
        this.poseSupplier = poseSupplier;
        this.robotRelativeSpeedsSupplier = robotRelativeSpeedsSupplier;
        this.robotRelativeSpeedsConsumer = robotRelativeSpeedsConsumer;
        this.shouldFlipPathSupplier = shouldFlipPathSupplier;
        this.poseResetConsumer = poseResetConsumer;
        setTranslationController(translationController);
        setRotationController(rotationController);
        configureControllers();
        
        addRequirements(driveSubsystem);
    }

    public FollowPath(
        Path path, 
        SubsystemBase driveSubsystem, 
        Supplier<Pose2d> poseSupplier, 
        Consumer<Pose2d> poseResetConsumer,
        Supplier<Boolean> shouldFlipPathSupplier,
        Supplier<ChassisSpeeds> robotRelativeSpeedsSupplier,
        Consumer<ChassisSpeeds> robotRelativeSpeedsConsumer
    ) {
        this(path, driveSubsystem, poseSupplier, robotRelativeSpeedsSupplier, robotRelativeSpeedsConsumer, shouldFlipPathSupplier, poseResetConsumer, translationController, rotationController);
    }

    @Override
    public void initialize() {
        if (translationController == null || rotationController == null) {
            throw new IllegalArgumentException("Translation and rotation controllers must be provided and must not be null");
        }

        if (!path.isValid()) {
            logger.log(java.util.logging.Level.WARNING, "FollowPath: Path invalid - skipping initialization");
            return;
        }

        if (shouldFlipPathSupplier.get()) {
            path.flip();
        }
        pathElementsWithConstraints = path.getPathElementsWithConstraintsNoWaypoints();


        rotationElementIndex = 0;
        translationElementIndex = 0;
        lastTimestamp = Timer.getTimestamp();
        pathInitStartPose = poseSupplier.get();
        lastSpeeds = ChassisSpeeds.fromRobotRelativeSpeeds(robotRelativeSpeedsSupplier.get(), pathInitStartPose.getRotation());
        previousRotationElementTargetRad = pathInitStartPose.getRotation().getRadians();
        previousRotationElementIndex = rotationElementIndex;
        currentRotationTargetInitRad = pathInitStartPose.getRotation().getRadians();
        rotationController.reset();
        translationController.reset();
        configureControllers();

        ArrayList<Translation2d> pathTranslations = new ArrayList<>();
        robotTranslations.clear();
        logCounter = 0;
        for (int i = 0; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                pathTranslations.add(((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation());
            }
        }
        Logger.recordOutput("FollowPath/pathTranslations", pathTranslations.toArray(Translation2d[]::new));

        // find the reset start pose. find the first translation target and use its translation as the start translation and the first rotation target as the start rotation.
        // if no rotation target, use the current robot rotation as the start rotation
        if (pathElementsWithConstraints.isEmpty()) {
            throw new IllegalStateException("Path must contain at least one element");
        }

        // Find first translation target
        Translation2d resetTranslation = null;
        for (Pair<PathElement, PathElementConstraint> element : pathElementsWithConstraints) {
            if (element.getFirst() instanceof TranslationTarget) {
                resetTranslation = ((TranslationTarget) element.getFirst()).translation();
                break;
            }
        }
        if (resetTranslation == null) {
            throw new IllegalStateException("Path must contain at least one translation target");
        }
        Rotation2d resetRotation = pathInitStartPose.getRotation();
        for (int i = 0; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof RotationTarget) {
                resetRotation = ((RotationTarget) pathElementsWithConstraints.get(i).getFirst()).rotation();
                break;
            }
        }
        poseResetConsumer.accept(new Pose2d(resetTranslation, resetRotation));
        
    }

    @Override
    public void execute() {
        if (!path.isValid()) {
            logger.log(java.util.logging.Level.WARNING, "FollowPath: Path invalid - skipping execution");
            return;
        }
        
        double dt = Timer.getTimestamp() - lastTimestamp;
        lastTimestamp = Timer.getTimestamp();


        Pose2d currentPose = poseSupplier.get();

        // Ensure we have valid indices
        if (translationElementIndex >= pathElementsWithConstraints.size()) {
            Logger.recordOutput("FollowPath/error", "Translation element index out of bounds");
            return;
        }
        if (!(pathElementsWithConstraints.get(translationElementIndex).getFirst() instanceof TranslationTarget)) {
            Logger.recordOutput("FollowPath/error", "Expected TranslationTarget at index " + translationElementIndex);
            return;
        }

        TranslationTarget currentTranslationTarget = (TranslationTarget) pathElementsWithConstraints.get(translationElementIndex).getFirst();
        // check to see if we are in the intermediate handoff radius of the current target translation
        if (currentPose.getTranslation().getDistance(currentTranslationTarget.translation()) <= 
            currentTranslationTarget.intermediateHandoffRadiusMeters().orElse(path.getDefaultGlobalConstraints().getIntermediateHandoffRadiusMeters())) {
            // if we are in the intermediate handoff radius of the current target translation,
            // switch to the next translation element
            for (int i = translationElementIndex + 1; i < pathElementsWithConstraints.size(); i++) {
                if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                    translationElementIndex = i;
                    break;
                }
            }
            currentTranslationTarget = (TranslationTarget) pathElementsWithConstraints.get(translationElementIndex).getFirst();
        }

        // Switch rotation targets based on progress along the path
        // Find the next rotation target that we haven't reached yet

        int lastRotationElementIndex = rotationElementIndex;
        while (rotationElementIndex < pathElementsWithConstraints.size()) {
            PathElement currentElement = pathElementsWithConstraints.get(rotationElementIndex).getFirst();

            // Skip non-rotation targets
            if (!(currentElement instanceof RotationTarget)) {
                rotationElementIndex++;
                continue;
            }

            // Check if we should stay at this rotation target or move to the next
            if (isRotationTRatioGreater()) {
                // We haven't reached this target's t_ratio yet, so stay here
                break;
            } else {
                // We've passed this target's t_ratio, log and move to next
                Logger.recordOutput("FollowPath/rotationElementIndex", rotationElementIndex);
                rotationElementIndex++;

                // If we've reached the end, stop
                if (rotationElementIndex >= pathElementsWithConstraints.size()) {
                    break;
                }
                // Continue searching for the next valid rotation target
            }
        }

        if (lastRotationElementIndex != rotationElementIndex &&
            pathElementsWithConstraints.get(lastRotationElementIndex).getFirst() instanceof RotationTarget) {
            previousRotationElementTargetRad = ((RotationTarget) pathElementsWithConstraints.get(lastRotationElementIndex).getFirst()).rotation().getRadians();
            previousRotationElementIndex = lastRotationElementIndex;
            currentRotationTargetInitRad = currentPose.getRotation().getRadians();
        }

        Translation2d targetTranslation = null;
        if (translationElementIndex < pathElementsWithConstraints.size() &&
            pathElementsWithConstraints.get(translationElementIndex).getFirst() instanceof TranslationTarget) {
            targetTranslation = ((TranslationTarget) pathElementsWithConstraints.get(translationElementIndex).getFirst()).translation();
        } else {
            // Fallback to current pose if no valid translation target
            targetTranslation = currentPose.getTranslation();
        }
        double remainingDistance = calculateRemainingPathDistance();
        double angleToTarget = Math.atan2(
            targetTranslation.getY() - currentPose.getTranslation().getY(),
            targetTranslation.getX() - currentPose.getTranslation().getX()
        );
        double translationControllerOutput = -translationController.calculate(remainingDistance, 0);

        // Cache the remaining distance for logging
        cachedRemainingDistance = remainingDistance;
        double vx = translationControllerOutput * Math.cos(angleToTarget);
        double vy = translationControllerOutput * Math.sin(angleToTarget);

        double targetRotation;
        RotationTargetConstraint rotationConstraint;

        if (rotationElementIndex < pathElementsWithConstraints.size() &&
            pathElementsWithConstraints.get(rotationElementIndex).getFirst() instanceof RotationTarget) {

            RotationTarget currentRotationTarget = (RotationTarget) pathElementsWithConstraints.get(rotationElementIndex).getFirst();
            if (!(pathElementsWithConstraints.get(rotationElementIndex).getSecond() instanceof RotationTargetConstraint)) {
                Logger.recordOutput("FollowPath/error", "Expected RotationTargetConstraint at index " + rotationElementIndex);
                return;
            }
            rotationConstraint = (RotationTargetConstraint) pathElementsWithConstraints.get(rotationElementIndex).getSecond();
            currentRotationTargetRad = currentRotationTarget.rotation();

            if (currentRotationTarget.profiledRotation()) {
                double remainingRotationDistance = calculateRemainingDistanceToRotationTarget();
                double rotationSegmentDistance = calculateRotationTargetSegmentDistance();

                Logger.recordOutput("FollowPath/remainingRotationDistance", remainingRotationDistance);
                Logger.recordOutput("FollowPath/rotationSegmentDistance", rotationSegmentDistance);

                // Avoid divide by zero and handle edge cases
                double segmentProgress = 0.0;
                if (rotationSegmentDistance > 1e-6) { // Use small epsilon instead of just > 0
                    segmentProgress = 1 - remainingRotationDistance / rotationSegmentDistance;
                    // Clamp to valid range
                    segmentProgress = Math.max(0.0, Math.min(1.0, segmentProgress));
                } else if (rotationSegmentDistance < 0) {
                    Logger.recordOutput("FollowPath/warning", "Negative rotation segment distance: " + rotationSegmentDistance);
                    segmentProgress = 0.0;
                }
                Logger.recordOutput("FollowPath/segmentProgress", segmentProgress);

                // Calculate the shortest angular path from current robot rotation to target
                double endRotation = currentRotationTarget.rotation().getRadians();
                double targetRotationDifference = endRotation - currentRotationTargetInitRad;
                double robotRotationDifference = endRotation - currentPose.getRotation().getRadians();
                double rotationDifference = MathUtil.angleModulus(endRotation - currentRotationTargetInitRad);
                // if (Math.abs(robotRotationDifference) > Math.abs(targetRotationDifference)) {
                //     rotationDifference = MathUtil.angleModulus(endRotation - currentRotationTargetInitRad) - 2 * Math.PI;
                // }
                // else {
                //     rotationDifference = MathUtil.angleModulus(endRotation - currentRotationTargetInitRad);
                // }


                // Normalize the rotation difference to [-π, π] to take shortest path
                

                // Interpolate along the shortest path
                targetRotation = currentRotationTargetInitRad + segmentProgress * rotationDifference;
            } else {
                targetRotation = MathUtil.angleModulus(currentRotationTarget.rotation().getRadians());
            }

        } else {
            targetRotation = previousRotationElementTargetRad;
            currentRotationTargetRad = new Rotation2d(targetRotation);
            rotationConstraint = new RotationTargetConstraint(
                    path.getDefaultGlobalConstraints().getMaxVelocityDegPerSec(), 
                    path.getDefaultGlobalConstraints().getMaxAccelerationDegPerSec2()
                );
        }
        double omega = rotationController.calculate(currentPose.getRotation().getRadians(), targetRotation);

        if (!(pathElementsWithConstraints.get(translationElementIndex).getSecond() instanceof TranslationTargetConstraint)) {
            Logger.recordOutput("FollowPath/error", "Expected TranslationTargetConstraint at index " + translationElementIndex);
            return;
        }
        TranslationTargetConstraint translationConstraint = (TranslationTargetConstraint) pathElementsWithConstraints.get(translationElementIndex).getSecond();

        ChassisSpeeds targetSpeeds = new ChassisSpeeds(vx, vy, omega);
        targetSpeeds = ChassisRateLimiter.limit(
            targetSpeeds, 
            lastSpeeds, 
            dt, 
            translationConstraint.maxAccelerationMetersPerSec2(),
            Math.toRadians(rotationConstraint.maxAccelerationDegPerSec2()),
            translationConstraint.maxVelocityMetersPerSec(),
            Math.toRadians(rotationConstraint.maxVelocityDegPerSec())
        );
        // targetSpeeds.omegaRadiansPerSecond = omega;

        robotRelativeSpeedsConsumer.accept(ChassisSpeeds.fromFieldRelativeSpeeds(targetSpeeds, currentPose.getRotation()));

        lastSpeeds = targetSpeeds;

        if (logCounter++ % 3 == 0) {
            robotTranslations.add(currentPose.getTranslation());

            // Limit memory usage by keeping only the most recent points
            if (robotTranslations.size() > 100) {
                // Remove oldest entries to keep only the last 75 points
                robotTranslations.subList(0, robotTranslations.size() - 75).clear();
            }

            Logger.recordOutput("FollowPath/robotTranslations", robotTranslations.toArray(Translation2d[]::new));
        }

        Logger.recordOutput("FollowPath/calculateRemainingPathDistance", cachedRemainingDistance);
        Logger.recordOutput("FollowPath/translationElementIndex", translationElementIndex);
        Logger.recordOutput("FollowPath/rotationElementIndex", rotationElementIndex);
        Logger.recordOutput("FollowPath/targetRotation", targetRotation);
        Logger.recordOutput("FollowPath/rotationControllerOutput", omega);

    }
    
    // initial test confirmed work
    private double calculateRemainingPathDistance() {
        Translation2d previousTranslation = poseSupplier.get().getTranslation();
        double remainingDistance = 0;
        for (int i = translationElementIndex; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                remainingDistance += previousTranslation.getDistance(
                    ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation()
                );
                previousTranslation = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
            }
        }
        return remainingDistance;
    }

    private double calculateRemainingDistanceToRotationTarget() {
        Translation2d previousTranslation = poseSupplier.get().getTranslation();
        double remainingDistance = 0;
        if (isRotationNextSegment()) {
            for (int i = translationElementIndex; i <= rotationElementIndex; i++) {
                if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                    remainingDistance += previousTranslation.getDistance(
                        ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation()
                    );
                    previousTranslation = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
                } else if (pathElementsWithConstraints.get(i).getFirst() instanceof RotationTarget) {
                    remainingDistance += previousTranslation.getDistance(
                        calculateRotationTargetTranslation(i)
                    );
                    previousTranslation = calculateRotationTargetTranslation(i);
                }
            }
        } else {
            remainingDistance = previousTranslation.getDistance(
                calculateRotationTargetTranslation(rotationElementIndex)
            );
        }
        
        return remainingDistance;
    }

    // total distance between the target rotation and the previous rotation target
    // if there is no previous rotation target, return the distance between the target rotation and the start of the path
    private double calculateRotationTargetSegmentDistance() {
        // Find the previous rotation target (immediately before current rotation)

        Translation2d startPoint = previousRotationElementIndex == 0
            ? pathInitStartPose.getTranslation()
            : calculateRotationTargetTranslation(previousRotationElementIndex);
        Translation2d endPoint = calculateRotationTargetTranslation(rotationElementIndex);

        double distance = 0.0;
        Translation2d prev = startPoint;
        int startIdx = previousRotationElementIndex == 0 ? 0 : previousRotationElementIndex + 1;
        for (int i = startIdx; i <= rotationElementIndex; i++) {
            if (i == rotationElementIndex) {
                distance += prev.getDistance(endPoint);
                break;
            }
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                Translation2d next = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
                distance += prev.getDistance(next);
                prev = next;
            }
        }
        return distance;
    }

    private Translation2d calculateRotationTargetTranslation(int index) {
        // Validate index
        if (index < 0 || index >= pathElementsWithConstraints.size()) {
            Logger.recordOutput("FollowPath/error", "Invalid index for calculateRotationTargetTranslation: " + index);
            return new Translation2d();
        }

        // find the two encompassing translation targets
        Translation2d translationA = null, translationB = null;
        for (int i = index; i >= 0; i--) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                translationA = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
                break;
            }
        }
        for (int i = index; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                translationB = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
                break;
            }
        }
        if (translationA == null && translationB == null) {
            Logger.recordOutput("FollowPath/error", "No translation targets found around rotation target at index " + index);
            return new Translation2d(); // Return default if no translation targets found
        }
        if (translationA == null) {
            return translationB;
        }
        if (translationB == null) {
            return translationA;
        }

        // If the two translation points are at the same location, return that location
        double segmentLength = translationA.getDistance(translationB);
        if (segmentLength == 0) {
            Logger.recordOutput("FollowPath/calculateRotationTargetTranslation", new Pose2d(translationA, new Rotation2d()));
            return translationA;
        }

        double angle = Math.atan2(
            translationB.getY() - translationA.getY(),
            translationB.getX() - translationA.getX()
        );

        if (index >= pathElementsWithConstraints.size() ||
            !(pathElementsWithConstraints.get(index).getFirst() instanceof RotationTarget)) {
            Logger.recordOutput("FollowPath/error", "Invalid rotation target index: " + index);
            return new Translation2d(); // Return a default value
        }

        double tRatio = ((RotationTarget) pathElementsWithConstraints.get(index).getFirst()).t_ratio();
        // Ensure tRatio is in valid range
        tRatio = Math.max(0.0, Math.min(1.0, tRatio));

        // Calculate the interpolated point along the segment
        double interpolatedDistance = segmentLength * tRatio;
        Translation2d pointOnSegment = new Translation2d(
            translationA.getX() + Math.cos(angle) * interpolatedDistance,
            translationA.getY() + Math.sin(angle) * interpolatedDistance
        );

        Logger.recordOutput("FollowPath/calculateRotationTargetTranslation", new Pose2d(pointOnSegment, new Rotation2d()));
        return pointOnSegment;
    }


    private boolean isRotationTRatioGreater() {
        if (isRotationNextSegment()) { return true; }
        if (isRotationPreviousSegment()) { return false; }
        if (rotationElementIndex >= pathElementsWithConstraints.size() ||
            !(pathElementsWithConstraints.get(rotationElementIndex).getFirst() instanceof RotationTarget)) { return false; }

        Pose2d currentPose = poseSupplier.get();

        // Find the segment that contains this rotation target
        Translation2d translationA = null;
        Translation2d translationB = null;

        // Find the translation target before the rotation target
        for (int i = rotationElementIndex - 1; i >= 0; i--) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                translationA = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
                break;
            }
        }

        // Find the translation target after the rotation target
        for (int i = rotationElementIndex + 1; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                translationB = ((TranslationTarget) pathElementsWithConstraints.get(i).getFirst()).translation();
                break;
            }
        }

        // If we can't find bounding translation targets, default behavior
        if (translationA == null || translationB == null) {
            return true; // Stay at current rotation target
        }

        double segmentLength = translationA.getDistance(translationB);
        if (segmentLength < 1e-6) {
            return true; // Avoid division by zero or very small segments
        }

        // Calculate progress along the segment (0 = at translationA, 1 = at translationB)
        double distanceFromA = currentPose.getTranslation().getDistance(translationA);
        double segmentProgress = distanceFromA / segmentLength;

        // Clamp progress to [0, 1]
        segmentProgress = Math.max(0, Math.min(1, segmentProgress));

        double targetTRatio = ((RotationTarget) pathElementsWithConstraints.get(rotationElementIndex).getFirst()).t_ratio();

        // Return true if we haven't reached the target t_ratio yet (should stay at current target)
        boolean shouldStayAtCurrentTarget = segmentProgress < targetTRatio;

        Logger.recordOutput("FollowPath/isRotationTRatioGreater", shouldStayAtCurrentTarget);
        Logger.recordOutput("FollowPath/segmentProgress", segmentProgress);
        Logger.recordOutput("FollowPath/targetTRatio", targetTRatio);

        return shouldStayAtCurrentTarget;
    }
    
    private boolean isRotationPreviousSegment() {
        if (rotationElementIndex > translationElementIndex) { return false; }
        
        for (int i = rotationElementIndex; i < translationElementIndex; i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                return true;
            }
        }
        return false;
    }
    private boolean isRotationNextSegment() {
        return rotationElementIndex > translationElementIndex;
    }

    @Override
    public boolean isFinished() {
        if (!path.isValid()) {
            logger.log(java.util.logging.Level.WARNING, "FollowPath: Path invalid - finishing early");
            return true;
        }

        // check if this is the last rotation element
        boolean isLastRotationElement = true;
        for (int i = rotationElementIndex+1; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof RotationTarget) {
                isLastRotationElement = false;
                break;
            }
        }
        // check if this is the last translation element (same pattern as rotation)
        boolean isLastTranslationElement = true;
        for (int i = translationElementIndex+1; i < pathElementsWithConstraints.size(); i++) {
            if (pathElementsWithConstraints.get(i).getFirst() instanceof TranslationTarget) {
                isLastTranslationElement = false;
                break;
            }
        }
        boolean finished = 
            isLastRotationElement && isLastTranslationElement && 
            translationController.atSetpoint() && 
            Math.abs(currentRotationTargetRad.minus(poseSupplier.get().getRotation()).getRadians()) < Math.toRadians(path.getEndRotationToleranceDeg());

        Logger.recordOutput("FollowPath/finished", finished);
        return finished;
    }
}