package com.ketroc.micro;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Buffs;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.DisplayType;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.ketroc.bots.Bot;
import com.ketroc.managers.ArmyManager;
import com.ketroc.utils.*;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class HellionHarasser extends Hellion {
    public static final Set<Units> VALID_TARGET_TYPES = Set.of(
            Units.ZERG_DRONE, Units.ZERG_DRONE_BURROWED, Units.ZERG_ZERGLING, Units.ZERG_ZERGLING_BURROWED,
            Units.ZERG_LARVA, Units.ZERG_CREEP_TUMOR_BURROWED, Units.ZERG_CREEP_TUMOR, Units.ZERG_CREEP_TUMOR_QUEEN,
            Units.ZERG_CHANGELING, Units.ZERG_CHANGELING_MARINE, Units.ZERG_CHANGELING_MARINE_SHIELD, Units.ZERG_LOCUS_TMP,
            Units.PROTOSS_PROBE, Units.PROTOSS_ZEALOT, Units.PROTOSS_DARK_TEMPLAR, Units.PROTOSS_HIGH_TEMPLAR,
            Units.PROTOSS_SENTRY, Units.TERRAN_SCV, Units.TERRAN_MULE, Units.TERRAN_HELLION_TANK);
    public static final Set<Units> CHASE_TARGETS = Set.of(
            Units.ZERG_DRONE, Units.ZERG_DRONE_BURROWED, Units.ZERG_CREEP_TUMOR_BURROWED, Units.ZERG_CREEP_TUMOR,
            Units.ZERG_CREEP_TUMOR_QUEEN, Units.ZERG_CHANGELING, Units.ZERG_CHANGELING_MARINE,
            Units.ZERG_CHANGELING_MARINE_SHIELD, Units.PROTOSS_PROBE, Units.PROTOSS_HIGH_TEMPLAR,
            Units.PROTOSS_SENTRY, Units.TERRAN_SCV, Units.TERRAN_MULE);

    public static final float HELLION_MOVEMENT_SIZE = (Bot.isRealTime) ? 4f : 2.5f;
    private List<Point2d> baseList;
    private boolean isDodgeClockwise;
    private int baseIndex = 1;
    private long prevDirectionChangeFrame;

    public HellionHarasser(Unit hellion, boolean isBaseTravelClockwise) {
        this(Bot.OBS.getUnit(hellion.getTag()), isBaseTravelClockwise);
    }

    public HellionHarasser(UnitInPool hellion, boolean isBaseTravelClockwise) {
        super(hellion, ArmyManager.attackGroundPos, MicroPriority.SURVIVAL);
        baseList = (isBaseTravelClockwise) ? LocationConstants.clockBasePositions : LocationConstants.counterClockBasePositions;
        baseList = baseList.subList(1, baseList.size());
        this.isDodgeClockwise = isBaseTravelClockwise;
        doDetourAroundEnemy = true;
    }

    public boolean isDodgeClockwise() {
        return isDodgeClockwise;
    }

    public void toggleDodgeClockwise() {
        isDodgeClockwise = !isDodgeClockwise;
        prevDirectionChangeFrame = Time.nowFrames();
    }

    //3sec delay between direction changes (so it doesn't get stuck wiggling against the edge)
    public boolean changedDirectionRecently() {
        return prevDirectionChangeFrame + 75 > Time.nowFrames();
    }

    private void nextBase() {
        baseIndex = (baseIndex + 1) % baseList.size();
    }

    private Point2d getThisBase() {
        return baseList.get(baseIndex);
    }


    @Override
    public void onStep() {
        //on death, remove
        if (!isAlive()) {
            onDeath();
            return;
        }

        //set target pos
        setTargetPos();

        //if can attack, find target
        if (UnitUtils.isWeaponAvailable(unit.unit())) {
            if (UnitUtils.isAttacking(unit.unit())) { //allow attack animation to complete
                return;
            }
            Optional<Unit> targetUnit = selectHarassTarget();

            //attack target
            if (targetUnit.isPresent()) {
                ActionHelper.unitCommand(unit.unit(), Abilities.ATTACK, targetUnit.get(), false);
                return;
            }

            //if at basePos without workers in vision, set target to next base
            else if (UnitUtils.getDistance(unit.unit(), getThisBase()) < 4f &&
                    UnitUtils.getVisibleEnemyUnitsOfType(UnitUtils.enemyWorkerType).stream()
                            .noneMatch(enemyWorker -> UnitUtils.getDistance(unit.unit(), enemyWorker) < 10)) {
                nextBase();
            }
        }

        //detour if unsafe
        if (!isSafe()) {
            detour();
            return;
        }

        //continue moving to target
        if (!isMovingToTargetPos()) {
            ActionHelper.unitCommand(unit.unit(), Abilities.MOVE, targetPos, false);
        }
    }

    private void setTargetPos() {
        //flee from closest cyclone, if locked on
        if (unit.unit().getBuffs().contains(Buffs.LOCK_ON)) {
            Unit nearestCyclone = UnitUtils.getClosestEnemyOfType(Units.TERRAN_CYCLONE, unit.unit().getPosition().toPoint2d());
            if (nearestCyclone != null) {
                targetPos = Position.towards(unit.unit().getPosition().toPoint2d(), nearestCyclone.getPosition().toPoint2d(), -4);
                return;
            }
        }

        //go towards nearest high priority enemy target (eg workers)
        Unit closestChaseTarget = UnitUtils.getVisibleEnemyUnitsOfType(CHASE_TARGETS).stream()
                .min(Comparator.comparing(enemy -> UnitUtils.getDistance(unit.unit(), enemy)))
                .orElse(null);
        if (closestChaseTarget != null && UnitUtils.getDistance(unit.unit(), closestChaseTarget) < 10) {
            targetPos = closestChaseTarget.getPosition().toPoint2d();
            return;
        }

        //go towards enemy base
        targetPos = getThisBase();
    }

    //selects target based on cost:health ratio
    public Optional<Unit> selectHarassTarget() { //TODO: calculate line splash damage
        return Bot.OBS.getUnits(Alliance.ENEMY,
                enemy -> !enemy.unit().getFlying().orElse(true) &&
                        UnitUtils.getDistance(enemy.unit(), unit.unit()) <= 4.9 &&
                        enemy.unit().getDisplayType() == DisplayType.VISIBLE &&
                        VALID_TARGET_TYPES.contains(enemy.unit().getType())).stream()
                .min(Comparator.comparing(enemyUip -> UnitUtils.numShotsToKill(unit.unit(), enemyUip.unit()) -
                            (UnitUtils.WORKER_TYPE.contains(enemyUip.unit().getType()) ? 1 : 0))) //make workers higher priority
                .map(UnitInPool::unit);
    }

}
