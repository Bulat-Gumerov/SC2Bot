package com.ketroc.strategies.defenses;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.ketroc.GameCache;
import com.ketroc.bots.Bot;
import com.ketroc.bots.KetrocBot;
import com.ketroc.micro.*;
import com.ketroc.models.Base;
import com.ketroc.models.StructureScv;
import com.ketroc.purchases.Purchase;
import com.ketroc.purchases.PurchaseStructure;
import com.ketroc.utils.Chat;
import com.ketroc.utils.LocationConstants;
import com.ketroc.utils.UnitUtils;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

//TODO: manageScvDefenders can work with worker scouts too, if isWorkerRushed is more accurate
public class WorkerRushDefense3 {
    public static boolean isWorkerRushed;

    public static void onStep() {
        manageScvDefenders();
        toggleIsWorkerRushed();
        if (isWorkerRushed) {
            UnitMicroList.removeAll(ScvAttackTarget.class); //free up scvs
            manageScvRepairers();
        }
    }

    private static void manageScvDefenders() {
        int numMarines = UnitMicroList.numOfUnitClass(MarineBasic.class);
        if (numMarines >= 3) {
            UnitMicroList.getUnitSubList(ScvDefender.class)
                    .forEach(scv -> {
                        UnitUtils.returnAndStopScv(scv.unit);
                        scv.removeMe = true;
                    });
            return;
        }
        int numAttackers = numEnemyWorkersAttacking();
        int numDefenders = UnitMicroList.numOfUnitClass(ScvDefender.class);
        int numDefendersNeeded = (int)(numAttackers * 1.34) - numDefenders;
        if (numDefendersNeeded > 0) {
            Comparator<Unit> compareByHealth = Comparator.comparing(scv -> scv.getHealth().orElse(0f));
            UnitUtils.getMyUnitsOfType(Units.TERRAN_SCV).stream()
                    .filter(scv -> scv.getHealth().orElse(0f) > 15)
                    .sorted(compareByHealth.reversed())
                    .limit(numDefendersNeeded)
                    .forEach(scv -> {
                        Base.releaseScv(scv);
                        UnitMicroList.add(new ScvDefender(scv));
                    });
        } else if (!isWorkerRushed && numDefendersNeeded < 0) {
            UnitMicroList.getUnitSubList(ScvDefender.class).stream()
                    .sorted(Comparator.comparing(scv -> scv.unit.unit().getHealth().orElse(0f)))
                    .limit(numDefendersNeeded * -1)
                    .forEach(scv -> {
                        UnitUtils.returnAndStopScv(scv.unit);
                        scv.removeMe = true;
                    });
        }
    }

    private static void manageScvRepairers() {
        if (Bot.OBS.getMinerals() >= 40) {
            List<Unit> lowHpScvs = UnitUtils.getMyUnitsOfType(Units.TERRAN_SCV).stream()
                    .filter(scv -> scv.getHealth().orElse(0f) <= 25 &&
                            UnitUtils.getDistance(scv, GameCache.baseList.get(0).getResourceMidPoint()) < 5)
                    .collect(Collectors.toList());
            ScvRepairer.getPendingRepairCost();
            while (lowHpScvs.size() >= 2) {
                UnitInPool scv1 = Bot.OBS.getUnit(lowHpScvs.remove(0).getTag());
                UnitInPool scv2 = Bot.OBS.getUnit(lowHpScvs.remove(0).getTag());
                UnitMicroList.add(new ScvRepairer(scv1, scv2));
                UnitMicroList.add(new ScvRepairer(scv2, scv1));
            }
        } else if (Bot.OBS.getMinerals() < 5) {
            cancelScvRepairers();
        }
    }

    private static void cancelScvRepairers() {
        UnitMicroList.removeAll(ScvRepairer.class);
    }

    private static void cancelScvDefenders() {
        UnitMicroList.getUnitSubList(ScvDefender.class)
                .forEach(scv -> {
                    UnitUtils.returnAndStopScv(scv.unit);
                    scv.removeMe = true;
                });
    }

    private static void changeBuildOrder() {
        //cancel gas, requeue it (after barracks)
        if (StructureScv.cancelProduction(Units.TERRAN_REFINERY)) {
            KetrocBot.purchaseQueue.addFirst(new PurchaseStructure(Units.TERRAN_REFINERY));
        }
        //move barracks to top of purchase queue (and position far from ramp)
        if (Purchase.isStructureQueued(Units.TERRAN_BARRACKS)) {
            Purchase.removeFirst(Units.TERRAN_BARRACKS);
            Point2d deepRaxPos = LocationConstants._3x3Structures.stream()
                    .filter(pos -> UnitUtils.isInMyMain(pos))
                    .max(Comparator.comparing(pos -> pos.distance(LocationConstants.myRampPos)))
                    .get();
            LocationConstants._3x3Structures.remove(deepRaxPos);
            KetrocBot.purchaseQueue.addFirst(new PurchaseStructure(Units.TERRAN_BARRACKS, deepRaxPos));
        }
    }

    private static void toggleIsWorkerRushed() {
        //turn on worker rush flag
        if (!isWorkerRushed) {
            if (Bot.OBS.getFoodArmy() < 3 && numEnemyWorkersAttacking() >= 5) {
                turnOnWorkerRushDefense();
            }
        //turn off worker rush flag
        } else if (UnitUtils.getVisibleEnemyUnitsOfType(UnitUtils.WORKER_TYPE).stream()
                .filter(enemy -> UnitUtils.getDistance(enemy, LocationConstants.myRampPos) <
                        UnitUtils.getDistance(enemy, LocationConstants.enemyRampPos))
                .count() == 0) {
            turnOffWorkerRushDefense();
        }
    }

    private static int numEnemyWorkersAttacking() {
        Predicate<Unit> defenseBoundary = (GameCache.baseList.get(1).isMyBase() || UnitUtils.getNatBunker().isPresent()) ?
                enemy -> UnitUtils.isInMyMainOrNat(enemy) :
                enemy -> UnitUtils.isInMyMain(enemy);
        return (int)UnitUtils.getVisibleEnemyUnitsOfType(UnitUtils.WORKER_TYPE).stream()
                .filter(defenseBoundary)
                .count();
    }

    private static void turnOffWorkerRushDefense() {
        isWorkerRushed = false;
        Chat.chat("Worker Defense Off");
        cancelScvDefenders();
    }

    private static void turnOnWorkerRushDefense() {
        isWorkerRushed = true;
        Chat.chat("Worker Defense On");
        Chat.tag("VS_WORKER_RUSH");
        changeBuildOrder();
    }
}
