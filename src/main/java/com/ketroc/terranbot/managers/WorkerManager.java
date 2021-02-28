package com.ketroc.terranbot.managers;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.debug.Color;
import com.github.ocraft.s2client.protocol.game.Race;
import com.github.ocraft.s2client.protocol.spatial.Point;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.UnitOrder;
import com.ketroc.terranbot.*;
import com.ketroc.terranbot.bots.KetrocBot;
import com.ketroc.terranbot.bots.Bot;
import com.ketroc.terranbot.micro.ScvAttackTarget;
import com.ketroc.terranbot.micro.TankToPosition;
import com.ketroc.terranbot.micro.UnitMicroList;
import com.ketroc.terranbot.models.*;
import com.ketroc.terranbot.purchases.Purchase;
import com.ketroc.terranbot.purchases.PurchaseStructure;
import com.ketroc.terranbot.strategies.CannonRushDefense;
import com.ketroc.terranbot.strategies.Strategy;
import com.ketroc.terranbot.utils.*;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


public class WorkerManager {
    public static int numScvsPerGas = 3;

    public static void onStep() {
        Strategy.setMaxScvs();
        repairLogic();
        //fix3ScvsOn1MineralPatch();
        if (Time.nowFrames()/Strategy.SKIP_FRAMES % 2 == 0) {
            fixOverSaturation();
        }
        else {
            putIdleScvsToWork();
        }
        toggleWorkersInGas();
        buildRefineryLogic();
        defendWorkerHarass(); //TODO: this method break scvrush micro
        preventMulesFromDyingWithMineralsInHand();
    }

    private static void putIdleScvsToWork() {
        if (Bot.OBS.getIdleWorkerCount() == 0) {
            return;
        }
        List<UnitInPool> idleScvs = UnitUtils.getIdleScvs();
        idleScvs.removeIf(scv -> Base.isMining(scv));

        idleScvs.removeIf(scv -> Base.assignScvToAMineralPatch(scv));
        if (!idleScvs.isEmpty()) {
            Chat.chatWithoutSpam("Using extra scvs to attack", 120);
            idleScvs.stream()
                    .forEach(scv -> Bot.ACTION.toggleAutocast(scv.getTag(), Abilities.EFFECT_REPAIR_SCV));
            ActionHelper.giveScvCommand(UnitUtils.toUnitList(idleScvs), Abilities.ATTACK, ArmyManager.groundAttackersMidPoint);
        }

//        //send extra scvs to closest undersaturated base
//        boolean basesOutOfMinerals = GameCache.baseList.stream()
//                .filter(base -> base.isMyBase() && !base.getMineralPatchUnits().isEmpty())
//                .count() == 0;
//        if (!basesOutOfMinerals) {
//            while (!idleScvs.isEmpty()) {
//                Unit scv = idleScvs.remove(0);
//                GameCache.baseList.stream()
//                        .filter(base -> base.isMyBase() && !base.getMineralPatchUnits().isEmpty())
//                        .min(Comparator.comparing(base -> UnitUtils.getDistance(scv, base.getMineralPatchUnits().get(0))))
//                        .ifPresent(closestBase -> closestBase.addMineralScv(scv));
//            }
//        }

//        //if my bases have no minerals
//        if (!idleScvs.isEmpty()) {
//            Chat.chatOnceOnly("No minerals left in my bases");
//            Unit nearestPatch = Bot.OBS.getUnits(Alliance.NEUTRAL, patch -> UnitUtils.MINERAL_NODE_TYPE.contains(patch.unit().getType())).stream()
//                    .min(Comparator.comparing(patch -> UnitUtils.getDistance(patch.unit(), GameCache.baseList.get(1).getCcPos())))
//                    .map(UnitInPool::unit)
//                    .orElse(null);
//            //distance-mine
//            if (nearestPatch != null) {
//                Chat.chatOnceOnly("distance mining");
//                Bot.ACTION.unitCommand(idleScvs, Abilities.SMART, nearestPatch, false);
//            }
//            //if no minerals to distance mine, join the attack as auto-repairers
//            else {
//                Chat.chatWithoutSpam("Using extra scvs to attack", 120);
//                idleScvs.stream()
//                        .forEach(scv -> Bot.ACTION.toggleAutocast(scv.getTag(), Abilities.EFFECT_REPAIR_SCV));
//                //UnitUtils.queueUpAttackOfEveryBase(scvsToMove);
//                Bot.ACTION.unitCommand(idleScvs, Abilities.ATTACK, ArmyManager.groundAttackersMidPoint, false);
//            }
//        }
    }

    //any mule in one of my bases that can't complete another mining round, will a-move + autorepair instead
    private static void preventMulesFromDyingWithMineralsInHand() {
        UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MULE).stream()
                .filter(mule -> UnitUtils.getOrder(mule) == Abilities.HARVEST_GATHER &&
                        mule.getBuffDurationRemain().orElse(0) < 144 &&
                        UnitUtils.getDistance(mule,
                                UnitUtils.getClosestUnitOfType(Alliance.SELF, UnitUtils.COMMAND_STRUCTURE_TYPE_TERRAN,
                                        mule.getPosition().toPoint2d())) < 3)
                .forEach(mule -> {
                    Bot.ACTION.unitCommand(mule, Abilities.ATTACK, ArmyManager.attackGroundPos, false);
                    Bot.ACTION.toggleAutocast(mule.getTag(), Abilities.EFFECT_REPAIR_MULE);
                });
    }

    private static void defendWorkerHarass() {
        //only for first 3min
        if (Time.nowFrames() > Time.toFrames("4:00") || CannonRushDefense.cannonRushStep != 0) {
            return;
        }

        //target scout workers
        UnitUtils.getVisibleEnemyUnitsOfType(UnitUtils.enemyWorkerType).forEach(enemyWorker -> {
            if (UnitUtils.isInMyMainOrNat(enemyWorker)) {
                ScvAttackTarget.add(enemyWorker);
            }
        });
    }

    private static void repairLogic() {  //TODO: don't repair wall if ranged units on other side
        if (Bot.OBS.getMinerals() < 15) {
            return;
        }

        //loop through units.  look for unmaxed health.  decide numscvs to repair
        List<Unit> unitsToRepair = new ArrayList<>();
        GameCache.baseList.stream()
                .filter(
                        base -> base.isMyBase() && (
                                base.getCc().unit().getType() == Units.TERRAN_PLANETARY_FORTRESS || (
                                        UnitUtils.getOrder(base.getCc().unit()) == Abilities.MORPH_PLANETARY_FORTRESS &&
                                        Time.nowFrames() - base.lastMorphFrame > 600
                                )
                        )
                ) //complete PFs or 10sec from morphed
                .forEach(base -> unitsToRepair.add(base.getCc().unit()));
        unitsToRepair.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MISSILE_TURRET));
        if (LocationConstants.opponentRace != Race.PROTOSS) { //libs on top of PF vs toss so unreachable by scvs to repair
            unitsToRepair.addAll(GameCache.liberatorList);
        }
        UnitMicroList.getUnitSubList(TankToPosition.class).forEach(tankToPosition -> unitsToRepair.add(tankToPosition.unit.unit()));
        if (InfluenceMaps.getValue(InfluenceMaps.pointThreatToGroundValue, LocationConstants.insideMainWall) < 2) {
            unitsToRepair.addAll(GameCache.wallStructures);
        }
        GameCache.burningStructures.forEach(structure -> {
            if (!InfluenceMaps.getValue(InfluenceMaps.pointThreatToGround, structure.getPosition().toPoint2d())) {
                unitsToRepair.add(structure);
            }
        });
        Unit natBunker = UnitUtils.getCompletedNatBunker();
        if (natBunker != null) {
            unitsToRepair.add(natBunker);
        }
        for (Unit unit : unitsToRepair) {
            int numScvsToAdd = UnitUtils.numIdealScvsToRepair(unit) - UnitUtils.numRepairingScvs(unit);
            if (numScvsToAdd <= 0) { //skip if no additional scvs required
                continue;
            }
            List<Unit> scvsForRepair = getScvsForRepairing(unit, numScvsToAdd);
            if (!scvsForRepair.isEmpty()) {
                Print.print("sending " + scvsForRepair.size() + " scvs to repair: " + unit.getType() + " at: " + unit.getPosition().toPoint2d());
                scvsForRepair.forEach(scv -> Base.releaseMineralScv(scv));
                //line up scvs behind PF before giving repair command
                if (unit.getType() == Units.TERRAN_PLANETARY_FORTRESS || UnitUtils.getOrder(unit) == Abilities.MORPH_PLANETARY_FORTRESS) {
                    Base pfBase = Base.getBase(unit);
                    Point2d behindPFPos = Position.towards(pfBase.getCcPos(), pfBase.getResourceMidPoint(), 5.4f);
                    for (Unit scv : scvsForRepair) {
                        if (pfBase != null && scvNotBehindPF(scv, pfBase)) {
                            Bot.ACTION.unitCommand(scv, Abilities.MOVE, behindPFPos, false)
                                    .unitCommand(scv, Abilities.EFFECT_REPAIR_SCV, unit, true);
                        }
                        else {
                            Bot.ACTION.unitCommand(scv, Abilities.EFFECT_REPAIR_SCV, unit, false);
                        }
                    }
                }
                else {
                    Bot.ACTION.unitCommand(scvsForRepair, Abilities.EFFECT_REPAIR_SCV, unit, false);
                }
            }
        }
    }

    private static boolean scvNotBehindPF(Unit unit, Base pfBase) {
        return UnitUtils.getDistance(unit, pfBase.getCcPos()) + 1.5 < UnitUtils.getDistance(unit, pfBase.getResourceMidPoint());
    }

    private static List<Unit> getScvsForRepairing(Unit unitToRepair, int numScvsToAdd) {
        List<Unit> availableScvs;
        if (numScvsToAdd > 9999) {
            availableScvs = getAllScvUnits(unitToRepair.getPosition().toPoint2d()).stream()
                    .filter(scv -> !UnitUtils.isScvRepairing(scv))
                    .collect(Collectors.toList());
        }
        else {
            //only choose scvs inside the wall within 20 distance
            if (GameCache.wallStructures.contains(unitToRepair) && !isRangedEnemyNearby()) {
                availableScvs = UnitUtils.toUnitList(Bot.OBS.getUnits(Alliance.SELF, u ->
                        u.unit().getType() == Units.TERRAN_SCV &&
                                Math.abs(u.unit().getPosition().getZ() - unitToRepair.getPosition().getZ()) < 1 && //same elevation as wall
                                UnitUtils.getDistance(u.unit(), unitToRepair) < 30 &&
                                (u.unit().getOrders().isEmpty() || isMiningMinerals(u))));
            }
//                        if (GameState.burningStructures.contains(unit) || GameState.wallStructures.contains(unit)) {
//                            //only send if safe
//                            //TODO: make threat to ground gridmap to check against (replace above if statement for wall structures)
//                        }
            else {
                availableScvs = UnitUtils.toUnitList(getAvailableScvs(unitToRepair.getPosition().toPoint2d()));
            }

            //sort by closest scvs then sublist
            availableScvs = availableScvs.stream()
                    .sorted(Comparator.comparing(scv -> UnitUtils.getDistance(scv, unitToRepair)))
                    .limit(Math.max(0, Math.min(availableScvs.size()-1, numScvsToAdd)))
                    .collect(Collectors.toList());
        }
        return availableScvs;
    }

    private static boolean isRangedEnemyNearby() {
        return InfluenceMaps.getValue(InfluenceMaps.pointThreatToGroundValue, LocationConstants.insideMainWall) > 0;
    }

    private static void buildRefineryLogic() {
        //don't build new refineries yet
//        if ((LocationConstants.opponentRace == Race.ZERG && GameCache.ccList.size() < 3) ||
//                (LocationConstants.opponentRace == Race.PROTOSS && GameCache.ccList.size() < 2) ||
//                (LocationConstants.opponentRace == Race.TERRAN && GameCache.ccList.size() < 2)) {
//            return;
//        }
        //don't make 3rd+ refinery until factory and PF are started
        if (Time.nowFrames() < Time.toFrames("5:00") &&
                (UnitUtils.getNumFriendlyUnits(Units.TERRAN_FACTORY, true) == 0 || !pfAtNatural())) {
            return;
        }

        //loop through bases
        for (Base base : GameCache.baseList) {
            if (base.isMyBase() && base.isComplete(0.60f)) {
                for (Gas gas : base.getGases()) {
                    if (gas.getRefinery() == null && gas.getGeyser().getVespeneContents().orElse(0) > Strategy.MIN_GAS_FOR_REFINERY) {
                        if (StructureScv.scvBuildingList.stream()
                                .noneMatch(scv -> scv.buildAbility == Abilities.BUILD_REFINERY && scv.structurePos.distance(gas.getPosition()) < 1)) {
                            if (!Purchase.isStructureQueued(Units.TERRAN_REFINERY)) {
                                KetrocBot.purchaseQueue.add(new PurchaseStructure(Units.TERRAN_REFINERY));
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean pfAtNatural() {
        Base natBase = GameCache.baseList.get(1);
        return natBase.isMyBase() &&
                (natBase.getCc().unit().getType() == Units.TERRAN_PLANETARY_FORTRESS ||
                        UnitUtils.getOrder(natBase.getCc().unit()) == Abilities.MORPH_PLANETARY_FORTRESS ||
                        Purchase.isMorphQueued(Abilities.MORPH_PLANETARY_FORTRESS));
    }

    public static List<Unit> getAvailableScvUnits(Point2d targetPosition) {
        return UnitUtils.toUnitList(getAvailableScvs(targetPosition, 9));
    }

    public static List<Unit> getAllScvUnits(Point2d targetPosition) {
        return UnitUtils.toUnitList(getAllScvs(targetPosition, 9));
    }

    public static UnitInPool getOneScv(Point2d targetPosition) {
        List<UnitInPool> oneScvList = getAvailableScvs(targetPosition, 20, false, true);
        return (!oneScvList.isEmpty()) ? oneScvList.get(0) : null;
    }
    public static UnitInPool getOneScv(Point2d targetPosition, int distance) {
        List<UnitInPool> oneScvList = getAvailableScvs(targetPosition, distance, true, true);
        return (!oneScvList.isEmpty()) ? oneScvList.get(0) : null;
    }
    public static UnitInPool getOneScv() {
        List<UnitInPool> oneScvList = getAvailableScvs(ArmyManager.retreatPos, Integer.MAX_VALUE, true, true);
        return (!oneScvList.isEmpty()) ? oneScvList.get(0) : null;
    }
    public static List<UnitInPool> getAvailableScvs(Point2d targetPosition) {
        return getAvailableScvs(targetPosition, 20, false, true);
    }
    public static List<UnitInPool> getAvailableScvs(Point2d targetPosition, int distance) {
        return getAvailableScvs(targetPosition, distance, true, true);
    }
    public static List<UnitInPool> getAllAvailableScvs() {
        return getAvailableScvs(ArmyManager.retreatPos, Integer.MAX_VALUE, true, true);
    }

    public static List<UnitInPool> getAvailableMineralScvs(Point2d targetPosition) {
        return getAvailableScvs(targetPosition, 20, false, false);
    }

    public static List<UnitInPool> getAvailableMineralScvs(Point2d targetPosition, int distance) {
        return getAvailableScvs(targetPosition, distance, true, false);
    }

    public static List<UnitInPool> getAvailableMineralScvs(Point2d targetPosition, int distance, boolean isDistanceEnforced) {
        return getAvailableScvs(targetPosition, distance, isDistanceEnforced, false);
    }

    public static List<UnitInPool> getAllAvailableMineralScvs() {
        return getAvailableScvs(ArmyManager.retreatPos, Integer.MAX_VALUE, true, false);
    }

    public static List<UnitInPool> getAvailableScvs(Point2d targetPosition, int distance, boolean isDistanceEnforced) {
        return getAvailableScvs(targetPosition, distance, isDistanceEnforced, true);
    }

    //return list of scvs that are mining minerals without holding minerals within an optional distance
    public static List<UnitInPool> getAvailableScvs(Point2d targetPosition, int distance, boolean isDistanceEnforced, boolean includeGasScvs) {
        List<UnitInPool> scvList = Bot.OBS.getUnits(Alliance.SELF, scv -> {
            return scv.unit().getType() == Units.TERRAN_SCV &&
                    (scv.unit().getOrders().isEmpty() || isMiningMinerals(scv) || (includeGasScvs && isMiningGas(scv))) &&
                    UnitUtils.getDistance(scv.unit(), targetPosition) < distance &&
                    !Ignored.contains(scv.getTag());
        });

//        List<UnitInPool> scvList = GameCache.availableScvs.stream()
//                .filter(scv -> Bot.QUERY.pathingDistance(scv.unit(), targetPosition) < distance)
//                .collect(Collectors.toList());

        if (scvList.isEmpty() && !isDistanceEnforced) {
            return getAvailableScvs(targetPosition, Integer.MAX_VALUE, true, includeGasScvs);
        }
        return scvList;
    }

    public static UnitInPool getClosestAvailableScv(Point2d targetPosition) {
        List<UnitInPool> scvList = getAvailableScvs(targetPosition, Integer.MAX_VALUE, true, true);
        return scvList.stream()
                .min(Comparator.comparing(scv -> UnitUtils.getDistance(scv.unit(), targetPosition)))
                .orElse(null);
    }

    //return list of all scvs within a distance
    public static List<UnitInPool> getAllScvs(Point2d targetPosition, int distance) {
        return Bot.OBS.getUnits(Alliance.SELF, scv ->
                scv.unit().getType() == Units.TERRAN_SCV &&
                !Ignored.contains(scv.getTag()) &&
                targetPosition.distance(scv.unit().getPosition().toPoint2d()) < distance);
    }


    public static boolean isMiningMinerals(UnitInPool scv) {
        return isMiningNode(scv, UnitUtils.MINERAL_NODE_TYPE);
    }

    public static boolean isMiningGas(UnitInPool scv) {
        return isMiningNode(scv, UnitUtils.REFINERY_TYPE);
    }

    private static boolean isMiningNode(UnitInPool scv, Set<Units> nodeType) { //TODO: doesn't include scvs returning minerals
        if (scv.unit().getOrders().size() != 1 || scv.unit().getOrders().get(0).getAbility() != Abilities.HARVEST_GATHER) {
            return false;
        }

        //if returning resources
        Optional<Tag> scvTargetTag = scv.unit().getOrders().get(0).getTargetedUnitTag();
        if (scvTargetTag.isEmpty()) { //return false if scv has no target
            return false;
        }

        UnitInPool targetNode = Bot.OBS.getUnit(scvTargetTag.get());
        return targetNode != null && nodeType.contains(targetNode.unit().getType());
    }



    private static void fixOverSaturation() {
        List<Unit> scvsToMove = new ArrayList<>();
        boolean mainBaseUnderAttack = mainBaseUnderAttack();

        //saturate gas and create list of extra scvs
        for (Base base : GameCache.baseList) {
            if (base.isReadyForMining()) {
                Unit cc = base.getCc().unit();

                //get available scvs at this base
                List<UnitInPool> baseMineralScvs = getAvailableMineralScvs(base.getCcPos(), 9);

                //saturate refineries
                for (Gas gas : base.getGases()) {
                    if (gas.getRefinery() != null) {
                        Unit refinery = gas.getRefinery();
                        int numScvsRequiredForGas = (base.isGasUnderLiberationZone(refinery)) ? 0 : numScvsPerGas; //Don't populate this gas if covered by a liberation zone
                        for (int i = refinery.getAssignedHarvesters().get(); i < Math.min(refinery.getIdealHarvesters().get(), numScvsRequiredForGas) && !baseMineralScvs.isEmpty(); i++) {
                            UnitInPool closestUnit = UnitUtils.getClosestUnit(baseMineralScvs, refinery);
                            ActionHelper.giveScvCommand(closestUnit.unit(), Abilities.SMART, refinery);
                            baseMineralScvs.remove(closestUnit);
                            base.scvsAddedThisFrame--;
                        }
                        if (refinery.getAssignedHarvesters().get() > numScvsRequiredForGas) {
                             List<UnitInPool> availableGasScvs = Bot.OBS.getUnits(Alliance.SELF, u -> {
                                if (u.unit().getType() == Units.TERRAN_SCV && !u.unit().getOrders().isEmpty()) {
                                    UnitOrder order = u.unit().getOrders().get(0);
                                    return order.getTargetedUnitTag().isPresent() && order.getAbility() == Abilities.HARVEST_GATHER && order.getTargetedUnitTag().get().equals(refinery.getTag());
                                }
                                return false;
                             });
                             if (!availableGasScvs.isEmpty()) {
                                 Bot.ACTION.unitCommand(UnitUtils.toUnitList(availableGasScvs), Abilities.STOP, false);
                             }
                        }
                    }
                }
//
//                //add extra scvs to list
//                int numExtraScvs = base.numScvsNeeded() * -1;
//                if (numExtraScvs > 0) {
//                    List<UnitInPool> extraScvsList = baseMineralScvs.subList(0, Math.min(baseMineralScvs.size(), numExtraScvs));
//                    scvsToMove.addAll(UnitUtils.toUnitList(extraScvsList));
//                }
            }
        }

//        //send extra scvs to closest undersaturated base
//        AtomicBoolean basesAllSaturated = new AtomicBoolean(false);
//        while (!scvsToMove.isEmpty() && !basesAllSaturated.get()) {
//            GameCache.baseList.stream()
//                    .filter(base -> base.isMyBase() && base.numScvsNeeded() > 0)
//                    .min(Comparator.comparing(base -> UnitUtils.getDistance(scvsToMove.get(0), base.getMineralPatchUnits().get(0))))
//                    .ifPresentOrElse(base -> base.addMineralScv(scvsToMove.remove(0)), () -> basesAllSaturated.set(true));
//        }

        //increase gas workers if minerals are all saturated
        if (!scvsToMove.isEmpty() && numScvsPerGas < 3) {
            numScvsPerGas++;
        }
    }

    private static boolean mainBaseUnderAttack() {
//        int totalEnemyCostInMain = GameCache.allEnemiesList.stream()
//                .filter(enemy -> UnitUtils.canAttackGround(enemy.unit()) &&
//                        !UnitUtils.WORKER_TYPE.contains(enemy.unit().getType()) &&
//                        InfluenceMaps.getValue(InfluenceMaps.pointInMainBase, enemy.unit().getPosition().toPoint2d()))
//                .mapToInt(enemy -> {
//                    Cost enemyCost = UnitUtils.getCost(enemy.unit());
//                    return enemyCost.minerals + enemyCost.gas;
//                })
//                .sum();

        return true;
    }

    public static void toggleWorkersInGas() {
        //skip logic until there are at least 2 refineries
        int numRefineries = UnitUtils.getNumFriendlyUnits(UnitUtils.REFINERY_TYPE, false);
        if (numRefineries <= 1) {
            return;
        }

        //max gas during slow 3rd base build order
        if (Strategy.EXPAND_SLOWLY && Time.nowFrames() < Time.toFrames("5:00")) {
            numScvsPerGas = 3;
            return;
        }

        int mins = GameCache.mineralBank;
        int gas = GameCache.gasBank;
        if (numScvsPerGas == 1) {
            if (gasBankRatio() < 0.6) {
                numScvsPerGas = 2;
            }
        }
        else if (numScvsPerGas == 2) {
            //if late game with bank, or if >3:1 mins:gas, then max gas income
            if (Time.nowFrames() > Time.toFrames("3:00") && (mins > 3100 || (mins > 300 && gasBankRatio() < 0.3))) {
                numScvsPerGas = 3;
            }
            //go to 1 in gas
            else if (gas > 700 && gasBankRatio() > 0.75) {
                numScvsPerGas = 1;
            }
        }
        else if (numScvsPerGas == 3) {
            if (mins < 2750 && gas > 80*GameCache.starportList.size() && gasBankRatio() > 0.5) {
                numScvsPerGas = 2;
            }
        };
    }

    private static float gasBankRatio() {
        return Math.max(GameCache.gasBank, 1f) / (Math.max(GameCache.gasBank, 1f) + Math.max(GameCache.mineralBank, 1f));
    }

    private static List<Unit> getDeepestMineralScvs(int numScvs) {
        List<UnitInPool> scvs = new ArrayList<>();
        int scvsNeeded = numScvs;
        for (Base base : GameCache.baseList) {
            if (base.isMyBase()) {
                List<UnitInPool> baseScvs = getAvailableScvs(base.getCcPos(), 9, true, true);
                if (baseScvs.size() >= scvsNeeded) {
                    scvs.addAll(baseScvs.subList(0, scvsNeeded));
                    break;
                } else {
                    scvs.addAll(baseScvs);
                    scvsNeeded -= baseScvs.size();
                }
            }
        }
        return scvs.stream().map(UnitInPool::unit).collect(Collectors.toList());
    }

    //Up a new pf base to a minimum of 10 scvs (12 for nat)
    public static void sendScvsToNewPf(Unit pf) { //TODO: fix this to create MineralPatch scvs at natural
        Point2d pfPos = pf.getPosition().toPoint2d();

        //transfer a lot to nat PF for early rushes
        if (pfPos.distance(LocationConstants.baseLocations.get(1)) < 1 && Base.numMyBases() == 2) {
            List<UnitInPool> mainBaseScvs = WorkerManager.getAllScvs(LocationConstants.baseLocations.get(0), 9);
            if (mainBaseScvs.size() > 11) {
                mainBaseScvs = mainBaseScvs.subList(0, 11);
            }
            mainBaseScvs.forEach(scv -> {
                    if (UnitUtils.isCarryingResources(scv.unit())) {
                        Bot.ACTION.unitCommand(scv.unit(), Abilities.HARVEST_RETURN, false)
                                .unitCommand(scv.unit(), Abilities.SMART, GameCache.baseList.get(1).getMineralPatchUnits().get(0), true);
                    }
                    else {
                        Bot.ACTION.unitCommand(scv.unit(), Abilities.SMART, GameCache.baseList.get(1).getMineralPatchUnits().get(0), false);
                    }
            });
            return;
        }

        //normal transfer of 6 scvs for other bases
        int scvsNeeded = 8 - UnitUtils.getUnitsNearbyOfType(Alliance.SELF, Units.TERRAN_SCV, pfPos, 9).size();
        if (scvsNeeded <= 0) {
            return;
        }
        Unit targetNode = getMiningNodeAtBase(pfPos);
        if (targetNode == null) {
            return;
        }

        List<Unit> scvs = getDeepestMineralScvs(scvsNeeded);
        if (!scvs.isEmpty()) {
            Bot.ACTION.unitCommand(scvs, Abilities.SMART, targetNode, false);
        }
    }

    private static Unit getMiningNodeAtBase(Point2d basePos) {
        List<UnitInPool> mineralPatches = UnitUtils.getUnitsNearbyOfType(Alliance.NEUTRAL, UnitUtils.MINERAL_NODE_TYPE, basePos, 9);
        if (!mineralPatches.isEmpty()) {
            return mineralPatches.get(0).unit();
        }
        else {
            List<UnitInPool> refineries = UnitUtils.getUnitsNearbyOfType(Alliance.SELF, UnitUtils.REFINERY_TYPE, basePos, 9);
            if (!refineries.isEmpty()) {
                return refineries.get(0).unit();
            }
        }
        return null;
    }
}
