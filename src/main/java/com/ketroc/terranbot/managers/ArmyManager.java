package com.ketroc.terranbot.managers;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.game.Race;
import com.github.ocraft.s2client.protocol.observation.raw.Visibility;
import com.github.ocraft.s2client.protocol.query.QueryBuildingPlacement;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.*;
import com.ketroc.terranbot.*;
import com.ketroc.terranbot.bots.Bot;
import com.ketroc.terranbot.micro.Target;
import com.ketroc.terranbot.models.Base;
import com.ketroc.terranbot.models.Cost;
import com.ketroc.terranbot.models.DefenseUnitPositions;
import com.ketroc.terranbot.models.StructureScv;
import com.ketroc.terranbot.strategies.CannonRushDefense;
import com.ketroc.terranbot.strategies.BunkerContain;
import com.ketroc.terranbot.strategies.Strategy;
import com.ketroc.terranbot.utils.InfluenceMaps;
import com.ketroc.terranbot.utils.LocationConstants;
import com.ketroc.terranbot.utils.Position;
import com.ketroc.terranbot.utils.UnitUtils;

import java.util.*;
import java.util.stream.Collectors;

public class ArmyManager {
    public static boolean doOffense;
    public static Point2d retreatPos;
    public static Point2d attackGroundPos;
    public static Point2d attackAirPos;
    public static Point2d defensePos;
    public static int numAutoturretsAvailable;
    public static Unit attackUnit;
    public static List<Unit> armyRetreating;
    public static List<Unit> armyGroundAttacking;
    public static List<Unit> armyAirAttacking;
    public static long prevSeekerFrame;
    public static int turretsCast;
    public static int queriesMade;

    public static void onStep() {
        //set defense position
        setDefensePosition();

        //set offense decision
        setOffense();

        // lift & lower depot walls
        raiseAndLowerDepots();

        //set attack location
        setAttackLocation();

        //respond to nydus
        nydusResponse();

        //pf targetting
        pfTargetting();

        //positioning siege tanks && tank targetting
        if (BunkerContain.proxyBunkerLevel != 2) {
            positionTanks();
        }

        //position liberators
        positionLiberators();

        //empty bunker after PF at natural is done
        emptyBunker();

        //position marines
        if (BunkerContain.proxyBunkerLevel == 0) {
            positionMarines();
        }

        //repair station
        manageRepairBay();

        //if searching for last structures
        if (attackGroundPos == null && Switches.finishHim) {
            spreadArmy(GameCache.bansheeList);
            spreadArmy(GameCache.vikingList);
        }
        else {
            armyRetreating = new ArrayList<>();
            armyGroundAttacking = new ArrayList<>();
            armyAirAttacking = new ArrayList<>();

            bansheeMicro();
            vikingMicro();
            ravenMicro();

            //send actions
            if (!armyRetreating.isEmpty()) {
                Bot.ACTION.unitCommand(armyRetreating, Abilities.MOVE, retreatPos, false);
            }
            if (!armyGroundAttacking.isEmpty()) {
                Point2d targetPos = (doOffense) ? attackGroundPos : defensePos;
                Bot.ACTION.unitCommand(armyGroundAttacking, Abilities.ATTACK, targetPos, false);
            }
            if (!armyAirAttacking.isEmpty()) {
                Point2d targetPos = (doOffense) ? attackAirPos : defensePos;
                Bot.ACTION.unitCommand(armyAirAttacking, Abilities.ATTACK, targetPos, false);
            }

            autoturretTargetting();
        }

        //send out marine+hellbat army
        sendMarinesHellbats();
    }

    private static void sendMarinesHellbats() {
        if (Cost.isGasBroke()) {
            List<Unit> army = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MARINE);
            army.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_HELLION_TANK));
            if (army.size() >= 20 || Bot.OBS.getFoodUsed() >= 199 || Cost.isMineralBroke()) {
                if (army.stream().anyMatch(unit -> !unit.getOrders().isEmpty())) {
                    Bot.ACTION.unitCommand(army, Abilities.ATTACK, attackGroundPos, false);
                }
            }
        }
    }

    private static void autoturretTargetting() {
        UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_AUTO_TURRET).stream()
                .filter(turret -> turret.getWeaponCooldown().orElse(1f) == 0)
                .forEach(turret -> {
                    selectTarget(turret).ifPresent(target ->
                            Bot.ACTION.unitCommand(turret, Abilities.ATTACK, target, false));
                });
    }

    private static Optional<Unit> selectTarget(Unit turret) {
        List<UnitInPool> enemiesInRange = Bot.OBS.getUnits(Alliance.ENEMY, enemy ->
                UnitUtils.getDistance(enemy.unit(), turret) <= 8 &&
                !UnitUtils.IGNORED_TARGETS.contains(enemy.unit().getType()) &&
                !enemy.unit().getHallucination().orElse(false) &&
                enemy.unit().getDisplayType() == DisplayType.VISIBLE &&
                (enemy.unit().getCloakState().orElse(CloakState.NOT_CLOAKED) == CloakState.NOT_CLOAKED) ||
                        enemy.unit().getCloakState().orElse(CloakState.NOT_CLOAKED) == CloakState.CLOAKED_DETECTED);

        Target bestTarget = new Target(null, Float.MAX_VALUE, Float.MAX_VALUE); //best target will be lowest hp unit without barrier
        for (UnitInPool enemy : enemiesInRange) {
            float enemyHP = enemy.unit().getHealth().orElse(0f) + enemy.unit().getShield().orElse(0f);
            UnitTypeData enemyData = Bot.OBS.getUnitTypeData(false).get(enemy.unit().getType());
            float enemyCost;
            if (enemy.unit().getType() == UnitUtils.enemyWorkerType) { //inflate value of workers as they impact income
                enemyCost = 75;
            }
            else {
                enemyCost = enemyData.getMineralCost().orElse(1) + (enemyData.getVespeneCost().orElse(1) * 1.2f); //value gas more than minerals
            }
            float enemyValue = enemyHP/enemyCost;
            if (enemyValue < bestTarget.value && !enemy.unit().getBuffs().contains(Buffs.PROTECTIVE_BARRIER)) {
                bestTarget.update(enemy, enemyValue, enemyHP);
            }
        }
        return (bestTarget.unit == null) ? Optional.empty() : Optional.of(bestTarget.unit.unit());
    }

    private static void bansheeMicro() {
        //give banshee divers command
        if (Switches.bansheeDiveTarget != null) {
            if (!giveDiversCommand(GameCache.bansheeDivers, Switches.bansheeDiveTarget)) {
                Switches.bansheeDiveTarget = null;
            }
        }

        //give normal banshees their commands
        for (Unit banshee : GameCache.bansheeList) {
            giveBansheeCommand(banshee);
        }
    }

    private static void vikingMicro() {
        //give viking divers commands
        vikingDiverMicro();

        //give normal banshees their commands
        for (Unit viking : GameCache.vikingList) {
            giveVikingCommand(viking);
        }
    }

    private static void vikingDiverMicro() {
        if (Switches.vikingDiveTarget != null) {
            if (Switches.isDivingTempests) {
                List<Unit> moveVikings = new ArrayList<>();
                List<Unit> attackVikings = new ArrayList<>();
                if (!UnitUtils.isVisible(Switches.vikingDiveTarget)) { //TODO: handle it when vikings arrive at last known tempest location and still can't find the tempest
                    moveVikings.addAll(GameCache.vikingDivers);
                }
                else {
                    for (Unit viking : GameCache.vikingDivers) {
                        if (viking.getWeaponCooldown().get() == 0 && UnitUtils.getDistance(viking, Switches.vikingDiveTarget.unit()) < 8.5) {
                            attackVikings.add(viking);
                        } else {
                            moveVikings.add(viking);
                        }
                    }
                }
                if (!attackVikings.isEmpty()) {
                    if (Switches.vikingDiveTarget.unit().getCloakState().get() == CloakState.CLOAKED) {
                        //scan behind the tempest
                        if (UnitUtils.canScan()) {
                            List<Unit> orbitals = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_ORBITAL_COMMAND);
                            Bot.ACTION.unitCommand(orbitals, Abilities.EFFECT_SCAN, Position.towards(Switches.vikingDiveTarget.unit().getPosition().toPoint2d(), ArmyManager.retreatPos, -5), false);
                        }
                        Bot.ACTION.unitCommand(attackVikings, Abilities.MOVE, Switches.vikingDiveTarget.unit().getPosition().toPoint2d(), false);
                    }
                    else {
                        Bot.ACTION.unitCommand(attackVikings, Abilities.ATTACK, Switches.vikingDiveTarget.unit(), false);
                    }
                }
                if (!moveVikings.isEmpty()) {
                    Bot.ACTION.unitCommand(moveVikings, Abilities.MOVE, Switches.vikingDiveTarget.unit().getPosition().toPoint2d(), false);
                }
            }
            else {
                if (!giveDiversCommand(GameCache.vikingDivers, Switches.vikingDiveTarget)) {
                    Switches.vikingDiveTarget = null;
                }
            }
        }
    }

    private static void setOffense() {
        if (Strategy.MASS_RAVENS) {
            numAutoturretsAvailable = GameCache.ravenList.stream()
                    .filter(raven -> UnitUtils.getDistance(raven, attackGroundPos) < 25)
                    .mapToInt(raven -> raven.getEnergy().orElse(0f).intValue() / 50)
                    .sum();
            if (!doOffense && numAutoturretsAvailable > 15) {
                doOffense = true;
            }
            else if (doOffense && numAutoturretsAvailable < 4) {
                doOffense = false;
            }
        }
        else {
            doOffense = (Bot.OBS.getUpgrades().contains(Upgrades.BANSHEE_CLOAK) &&
                    GameCache.bansheeList.size() > 1);
        }
    }

    private static void ravenMicro() {
        turretsCast = 0;
        queriesMade = 0;

        //give ravens their commands
        for (Unit raven : GameCache.ravenList) {
            giveRavenCommand(raven, queriesMade <= 6);
        }
    }

    //set defensePos to closestEnemy to an injured PF
    private static void setDefensePosition() {
        for (Base base : GameCache.baseList) {
            if (base.isMyBase()) {
                Unit cc = base.getCc().get().unit();
                if (cc.getHealth().orElse(1500f) < cc.getHealthMax().orElse(1500f)) {
                    List<UnitInPool> nearbyEnemies = Bot.OBS.getUnits(Alliance.ENEMY, enemy ->
                            !enemy.unit().getHallucination().orElse(false) &&
                            enemy.unit().getType() != Units.ZERG_CHANGELING_MARINE &&
                            UnitUtils.getDistance(enemy.unit(), cc) < 15);
                    if (!nearbyEnemies.isEmpty()) {
                        defensePos = nearbyEnemies.stream()
                                .min(Comparator.comparing(enemy -> UnitUtils.getDistance(enemy.unit(), cc)))
                                .get().unit().getPosition().toPoint2d();
                        return;
                    }
                }
            }
        }
        defensePos = retreatPos;
    }


    private static boolean giveDiversCommand(List<Unit> divers, UnitInPool diveTarget) {
        //return false if diver list is empty
        if (divers.isEmpty()) {
            return false;
        }

        boolean canAttack = divers.get(0).getWeaponCooldown().orElse(1f) == 0f;
        float attackRange = Bot.OBS.getUnitTypeData(false).get(divers.get(0).getType()).getWeapons().iterator().next().getRange();
        List<Unit> attackers = new ArrayList<>();
        List<Unit> retreaters = new ArrayList<>();
        for (Unit diver : divers) {
            boolean inRange = UnitUtils.getDistance(diveTarget.unit(), diver) < attackRange;
            if (canAttack || !inRange) {
                attackers.add(diver);
            }
            else {
                retreaters.add(diver);
            }
        }
        if (!attackers.isEmpty()) {
            Bot.ACTION.unitCommand(attackers, Abilities.ATTACK, diveTarget.unit(), false);
        }
        if (!retreaters.isEmpty()) {
            Bot.ACTION.unitCommand(retreaters, Abilities.MOVE, retreatPos, false);
        }
        return true;
    }

    private static void manageRepairBay() {
        int numInjured = Bot.OBS.getUnits(Alliance.SELF, u -> { //get number of injured army units in dock
            return (u.unit().getType() == Units.TERRAN_VIKING_FIGHTER || u.unit().getType() == Units.TERRAN_BANSHEE || u.unit().getType() == Units.TERRAN_RAVEN) &&
                    UnitUtils.getHealthPercentage(u.unit()) < 100 &&
                    u.unit().getPosition().toPoint2d().distance(LocationConstants.REPAIR_BAY) < 5;
        }).size();
        if (numInjured > 0) {
            int numRepairingScvs = Bot.OBS.getUnits(Alliance.SELF, u -> { //get number of scvs currently repairing (ie, on attack move)
                return u.unit().getType() == Units.TERRAN_SCV &&
                        !u.unit().getOrders().isEmpty() &&
                        (u.unit().getOrders().get(0).getAbility() == Abilities.ATTACK || u.unit().getOrders().get(0).getAbility() == Abilities.EFFECT_REPAIR);
            }).size();  //TODO: move this to GameState.startFrame() ??
            int numScvsToSend = Strategy.NUM_SCVS_REPAIR_STATION - numRepairingScvs; //decide 5 or 10 total scvs to repair at dock
            if (numScvsToSend > 1) {
                List<Unit> availableScvs = UnitUtils.toUnitList(WorkerManager.getAvailableScvs(LocationConstants.REPAIR_BAY, 30, false)); //TODO: sort, or 2 calls? -so closest scvs repair
                if (availableScvs.size() > numScvsToSend) {
                    availableScvs = availableScvs.subList(0, numScvsToSend);
                }
                if (!availableScvs.isEmpty()) {
                    for (Unit scv : availableScvs) { //turn on autocast repair for all scvs selected
                        if (!scv.getBuffs().contains(Buffs.AUTOMATED_REPAIR)) {
                            Bot.ACTION.toggleAutocast(scv.getTag(), Abilities.EFFECT_REPAIR_SCV);
                        }
                    }
                    Bot.ACTION.unitCommand(availableScvs, Abilities.ATTACK, retreatPos, false); //a-move scvs to repair station and queue up mining minerals afterwards
                    //.unitCommand(availableScvs, Abilities.SMART, GameState.mineralNodeRally.unit(), true);
                }
            }
        }
    }

    private static void setAttackLocation() {
        UnitInPool closestEnemyGround = getClosestEnemyGroundUnit();
        if (closestEnemyGround != null) {
            attackGroundPos = closestEnemyGround.unit().getPosition().toPoint2d();
            attackUnit = closestEnemyGround.unit();
            //TODO: below is hack to "hopefully" handle unknown bug of air units getting stuck on unchanging attackPos
            if (!UnitUtils.getUnitsNearbyOfType(Alliance.SELF, Units.TERRAN_BANSHEE, attackGroundPos, 1).isEmpty() &&
                    !UnitUtils.getUnitsNearbyOfType(Alliance.SELF, Units.TERRAN_VIKING_FIGHTER, attackGroundPos, 1).isEmpty() &&
                    !UnitUtils.getUnitsNearbyOfType(Alliance.SELF, Units.TERRAN_RAVEN, attackGroundPos, 1).isEmpty()) {
                System.out.println("\n\n=============== PHANTOM ENEMY FOUND ===============\n");
                System.out.println("closestEnemyGround.isAlive() = " + closestEnemyGround.isAlive());
                System.out.println("closestEnemyGround.unit().getType() = " + closestEnemyGround.unit().getType());
                GameCache.allEnemiesList.remove(closestEnemyGround);
            }
        }
        else if (Switches.finishHim) {
            attackGroundPos = null; //flag to spread army
        }
        else {
            attackGroundPos = LocationConstants.baseLocations.get(LocationConstants.baseAttackIndex);
            //gone on to next base after a banshee, viking, and raven have arrived
            if (Bot.OBS.getVisibility(attackGroundPos) == Visibility.VISIBLE &&
                    (GameCache.bansheeList.isEmpty() || UnitUtils.isUnitTypesNearby(Alliance.SELF, Units.TERRAN_BANSHEE, attackGroundPos, 3)) &&
                    (GameCache.vikingList.size() < 3 || UnitUtils.isUnitTypesNearby(Alliance.SELF, Units.TERRAN_VIKING_FIGHTER, attackGroundPos, 3)) &&
                    (GameCache.ravenList.isEmpty() || GameCache.ravenList.stream()
                            .noneMatch(raven ->
                                    UnitUtils.getHealthPercentage(raven) >= Strategy.RETREAT_HEALTH &&
                                            UnitUtils.getDistance(raven, retreatPos) > 10) ||
                            UnitUtils.isUnitTypesNearby(Alliance.SELF, Units.TERRAN_RAVEN, attackGroundPos, 3))) {
                LocationConstants.rotateBaseAttackIndex();
                attackGroundPos = LocationConstants.baseLocations.get(LocationConstants.baseAttackIndex);
            }
        }

        //send single vikings at the closest non-threatening air, and set the main air attack target
        UnitInPool closestEnemyAir;
        do {
            closestEnemyAir = getClosestEnemyAirUnit();
        } while (doPeelOffVikingForEasyTarget(closestEnemyAir));
        attackAirPos = (closestEnemyAir != null) ? closestEnemyAir.unit().getPosition().toPoint2d() : attackGroundPos;

    }

    private static boolean doPeelOffVikingForEasyTarget(UnitInPool closestEnemyAir) {
        if (closestEnemyAir == null) {
            return false;
        }
        if (UnitUtils.NO_THREAT_ENEMY_AIR.contains(closestEnemyAir.unit().getType())) {
            Unit closestViking = GameCache.vikingList.stream()
                    .min(Comparator.comparing(unit -> UnitUtils.getDistance(closestEnemyAir.unit(), unit)))
                    .orElse(null);
            if (closestViking != null) {
                Bot.ACTION.unitCommand(closestViking, Abilities.ATTACK, closestEnemyAir.unit(), false);
                GameCache.vikingList.remove(closestViking);
            }
            GameCache.allVisibleEnemiesList.remove(closestEnemyAir);
            return true;
        }
        return false;
    }

    private static UnitInPool getClosestEnemyGroundUnit() {
        UnitInPool closestEnemyGroundUnit = GameCache.allVisibleEnemiesList.stream()
                .filter(u -> //(Switches.finishHim || u.unit().getDisplayType() != DisplayType.SNAPSHOT) && //ignore snapshot unless finishHim is true
                        !u.unit().getFlying().orElse(true) && //ground unit
                                u.unit().getCloakState().orElse(CloakState.NOT_CLOAKED) != CloakState.CLOAKED && //ignore cloaked units TODO: handle banshees DTs etc with scan
                                !u.unit().getBurrowed().orElse(false) && //ignore burrowed units TODO: handle with scan
                                u.unit().getType() != Units.ZERG_CHANGELING_MARINE && //ignore changelings
                                u.unit().getType() != Units.ZERG_BROODLING && //ignore broodlings
                                !u.unit().getHallucination().orElse(false) && //ignore hallucs
                                UnitUtils.isVisible(u)) //ignore units in the fog
                .min(Comparator.comparing(u -> UnitUtils.getDistance(u.unit(), LocationConstants.myMineralPos)))
                .orElse(null);
        if (closestEnemyGroundUnit == null) {
            return null;
        }

        //swap closest enemy base if it's closer than closest enemy unit
        UnitInPool closestEnemyBase = Bot.OBS.getUnits(Alliance.ENEMY, enemy -> UnitUtils.enemyCommandStructures.contains(enemy.unit().getType()))
                .stream()
                .min(Comparator.comparing(enemy -> UnitUtils.getDistance(enemy.unit(), LocationConstants.myMineralPos)))
                .orElse(null);
        if (closestEnemyBase != null &&
                UnitUtils.getDistance(closestEnemyBase.unit(), LocationConstants.myMineralPos) <
                        UnitUtils.getDistance(closestEnemyGroundUnit.unit(), LocationConstants.myMineralPos)) {
            return closestEnemyBase;
        }

        return closestEnemyGroundUnit;
    }

    private static UnitInPool getClosestEnemyAirUnit() {
        return GameCache.allVisibleEnemiesList.stream()
                .filter(u -> (Switches.finishHim || u.unit().getDisplayType() != DisplayType.SNAPSHOT) && //ignore snapshot unless finishHim is true
                        u.unit().getFlying().orElse(false) && //air unit
                        (!GameCache.ravenList.isEmpty() || u.unit().getCloakState().orElse(CloakState.NOT_CLOAKED) != CloakState.CLOAKED) && //ignore cloaked units TODO: handle banshees DTs etc with scan
                        u.unit().getType() != Units.ZERG_PARASITIC_BOMB_DUMMY &&
                        !u.unit().getHallucination().orElse(false) && UnitUtils.isVisible(u)) //ignore hallucs and units in the fog
                .min(Comparator.comparing(u -> u.unit().getPosition().toPoint2d().distance(LocationConstants.baseLocations.get(0)))).orElse(null);
    }

    private static void nydusResponse() {
        //send scvs, unsieged tanks, and bio to kill nydus
        Optional<UnitInPool> nydusWorm = UnitUtils.getEnemyUnitsOfType(Units.ZERG_NYDUS_CANAL).stream().findFirst();
        if (nydusWorm.isPresent()) {
            GameResult.setNydusRushed(); //TODO: temp for Spiny
            List<Unit> nydusDivers = new ArrayList<>();
            nydusDivers.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MARINE));
            nydusDivers.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MARAUDER));
            nydusDivers.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_SIEGE_TANK));
            //add 10 close scvs
            List<UnitInPool> scvs = Bot.OBS.getUnits(Alliance.SELF, scv ->
                    scv.unit().getType() == Units.TERRAN_SCV &&
                            Position.isSameElevation(scv.unit().getPosition(), nydusWorm.get().unit().getPosition()) &&
                            UnitUtils.getDistance(scv.unit(), nydusWorm.get().unit()) < 35 &&
                            !StructureScv.isScvProducing(scv.unit()))
                    .stream()
                    .sorted(Comparator.comparing(scv -> Bot.QUERY.pathingDistance(scv.unit(), nydusWorm.get().unit().getPosition().toPoint2d())))
                    .collect(Collectors.toList());
            if (scvs.size() > 10) {
                scvs.subList(10, scvs.size()).clear();
            }
            nydusDivers.addAll(UnitUtils.toUnitList(scvs));
            attackGroundPos = nydusWorm.get().unit().getPosition().toPoint2d();
            attackUnit = nydusWorm.get().unit();
            if (!nydusDivers.isEmpty()) {
                Bot.ACTION.unitCommand(nydusDivers, Abilities.ATTACK, ArmyManager.attackUnit, false);
            }

            //also set banshee dive target to nydus
            if (!GameCache.bansheeList.isEmpty()) {
                GameCache.bansheeDivers.addAll(GameCache.bansheeList);
                Switches.bansheeDiveTarget = nydusWorm.get();
            }
        }
    }

    public static void pfTargetting() {
        List<Unit> pfsAndTanks = new ArrayList<>();
        pfsAndTanks.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_SIEGE_TANK_SIEGED));
        pfsAndTanks.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_PLANETARY_FORTRESS));
        pfsAndTanks = pfsAndTanks.stream()
                .filter(unit -> unit.getBuildProgress() == 1 &&
                        unit.getWeaponCooldown().orElse(1f) == 0 &&
                        InfluenceMaps.getValue(InfluenceMaps.pointGroundUnitWithin13, unit.getPosition().toPoint2d()))
                .collect(Collectors.toList());

        for (Unit pfTank : pfsAndTanks) {
            int range;
            float x_pfTank = pfTank.getPosition().getX();
            float y_pfTank = pfTank.getPosition().getY();

            //siege tank range - 1 for rounding
            if (pfTank.getType() == Units.TERRAN_SIEGE_TANK_SIEGED) {
                range = 12;
            }
            //pf range + 2.5 for PF radius +1 for hisec - 0.5 for rounding
            else if (Bot.OBS.getUpgrades().contains(Upgrades.HISEC_AUTO_TRACKING)) {
                range = 9;
            }
            else {
                range = 8;
            }

            int xMin = 0; //(int) LocationConstants.SCREEN_BOTTOM_LEFT.getX();
            int xMax = InfluenceMaps.toMapCoord(LocationConstants.SCREEN_TOP_RIGHT.getX());
            int yMin = 0; //(int) LocationConstants.SCREEN_BOTTOM_LEFT.getY();
            int yMax = InfluenceMaps.toMapCoord(LocationConstants.SCREEN_TOP_RIGHT.getY());
            int xStart = Math.max(Math.round(2*(x_pfTank - range)), xMin);
            int yStart = Math.max(Math.round(2*(y_pfTank - range)), yMin);
            int xEnd = Math.min(Math.round(2*(x_pfTank + range)), xMax);
            int yEnd = Math.min(Math.round(2*(y_pfTank + range)), yMax);


            //get x,y of max value
            int bestValueX = -1;
            int bestValueY = -1;
            int bestValue = 0;
            for (int x = xStart; x <= xEnd; x++) {
                for (int y = yStart; y <= yEnd; y++) {
                    if (InfluenceMaps.pointPFTargetValue[x][y] > bestValue &&
                            Position.distance(x/2f, y/2f, x_pfTank, y_pfTank) < range) {
                        bestValueX = x;
                        bestValueY = y;
                        bestValue = InfluenceMaps.pointPFTargetValue[x][y];

                    }
                }
            }
            if (bestValue == 0) {
                return;
            }

            Point2d bestTargetPos = Point2d.of(bestValueX/2f, bestValueY/2f);

            //get enemy Unit near bestTargetPos
            List<UnitInPool> enemyTargets = Bot.OBS.getUnits(Alliance.ENEMY, enemy ->
                    UnitUtils.getDistance(enemy.unit(), bestTargetPos) < 1f &&
                    !enemy.unit().getFlying().orElse(false));

            //attack enemy Unit
            if (!enemyTargets.isEmpty()) {
                Bot.ACTION.unitCommand(pfTank, Abilities.ATTACK, enemyTargets.get(0).unit(), false);
            }
        }
    }

    private static void positionMarines() {
        List<Unit> bunkerList = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_BUNKER);
        if (!bunkerList.isEmpty()) {
            for (Unit marine : UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MARINE)) {
                if (marine.getOrders().isEmpty()) { //for each idle marine
                    Bot.ACTION.unitCommand(marine, Abilities.SMART, bunkerList.get(0), false);
                }
            }
        }
    }

//    private static void positionSiegeTanks() {
//        List<UnitInPool> newTanks = UnitUtils.getUnitsNearbyOfType(Alliance.SELF, Units.TERRAN_SIEGE_TANK, LocationConstants.insideMainWall, 8);
//        for (Base base : GameCache.baseList) {
//            //skip main base
//            if (!base.isMyBase() || base.getCc().get().unit().getType() == Units.TERRAN_ORBITAL_COMMAND) {
//                continue;
//            }
//
//            List<UnitInPool> siegedTanks = base.getSiegedTanks();
//            List<UnitInPool> unsiegedTanks = base.getUnsiegedTanks();
//            int numTanks = siegedTanks.size() + unsiegedTanks.size();
//            List<Unit> largeMinerals = base.getLargeMinerals();
//
//            //free up tanks if base is near dry
//            if (largeMinerals.size() < Strategy.NUM_TANKS_PER_EXPANSION) {
//                if (!siegedTanks.isEmpty()) {
//                    Bot.ACTION.unitCommand(UnitUtils.unitInPoolToUnitList(siegedTanks), Abilities.MORPH_UNSIEGE, false);
//                }
//                newTanks.addAll(unsiegedTanks);
//                continue;
//            }
//
//            //send new tanks to base
//            while (numTanks < Strategy.NUM_TANKS_PER_EXPANSION && !newTanks.isEmpty()) {
//                Bot.ACTION.unitCommand(newTanks.remove(0).unit(), Abilities.ATTACK, base.getCcPos(), false);
//                numTanks++;
//            }
//
//            //add extra tanks at base to newTank list
//            while (numTanks > Strategy.NUM_TANKS_PER_EXPANSION && !unsiegedTanks.isEmpty()) {
//                newTanks.add(unsiegedTanks.remove(0));
//                numTanks--;
//            }
//
//            //if there is idle unsieged tanks then position and siege them
//            if (!unsiegedTanks.isEmpty() && unsiegedTanks.stream().allMatch(tank -> tank.unit().getOrders().isEmpty())) {
//                List<Unit> outerNodes = base.getOuterBigPatches();
//                for (Unit node : outerNodes) {
//                    if (unsiegedTanks.isEmpty()) {
//                        break;
//                    }
//                    Point2d mineralPos = node.getPosition().toPoint2d();
//                    if (!UnitUtils.isUnitTypesNearby(Alliance.SELF, UnitUtils.SIEGE_TANK_TYPE, mineralPos, 2.5f)) {
//                        Unit tank = unsiegedTanks.remove(0).unit();
//                        Bot.ACTION.unitCommand(tank, Abilities.ATTACK, Position.towards(mineralPos, base.getCcPos(), 2), false)
//                                .unitCommand(tank, Abilities.MORPH_SIEGE_MODE, true);
//                    }
//                }
//            }
//        }
//
//        //send left over free tanks to main base
//        if (!newTanks.isEmpty()) {
//            Bot.ACTION.unitCommand(UnitUtils.unitInPoolToUnitList(newTanks), Abilities.ATTACK, LocationConstants.insideMainWall, false);
//        }
//    }

//    private static void positionLiberators() {
//        List<Unit> availableLibs = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_LIBERATOR).stream()
//                .filter(unit -> unit.getOrders().isEmpty() && UnitUtils.getDistance(unit, ArmyManager.retreatPos) < 3)
//                .collect(Collectors.toList());
//        for (int i = GameCache.baseList.size()-1; i>=0; i--) {
//            Base base = GameCache.baseList.get(i);
//
//            //skip main base
//            if (!base.isMyBase() || base.getCc().get().unit().getType() == Units.TERRAN_ORBITAL_COMMAND) {
//                continue;
//            }
//
//            List<Unit> siegedLibs = base.getSiegedLibs();
//            List<Unit> unsiegedLibs = base.getUnsiegedLibs();
//            int numLibs = siegedLibs.size() + unsiegedLibs.size();
//
//            //free up libs if base is near dry or if there are 4 newer bases
//            if (base.getMineralPatches().size() < 3 || Base.numMyBases() - i > Strategy.MAX_BASES_TO_DEFEND) {
//                if (!siegedLibs.isEmpty()) {
//                    Bot.ACTION.unitCommand(siegedLibs, Abilities.MORPH_LIBERATOR_AA_MODE, false);
//                }
//                availableLibs.addAll(unsiegedLibs);
//                continue;
//            }
//
//            //send available libs to base
//            while (numLibs < Strategy.NUM_LIBS_PER_EXPANSION && !availableLibs.isEmpty()) {
//                Bot.ACTION.unitCommand(availableLibs.remove(0), Abilities.ATTACK, base.getCcPos(), false);
//                numLibs++;
//            }
//
//            //add extra libs at base to availableLibs list
//            while (numLibs > Strategy.NUM_LIBS_PER_EXPANSION && !unsiegedLibs.isEmpty()) {
//                availableLibs.add(unsiegedLibs.remove(0));
//                numLibs--;
//            }
//
//            //if there is idle unsieged libs then position and siege them
//            if (!unsiegedLibs.isEmpty() && unsiegedLibs.stream().allMatch(lib -> lib.getOrders().isEmpty())) {
//                List<Point2d> libPositions = base.getLiberatorPositions();
//                for (Point2d libPos : libPositions) {
//                    if (unsiegedLibs.isEmpty()) {
//                        break;
//                    }
//                    if (!UnitUtils.isUnitTypesNearby(Alliance.SELF, Units.TERRAN_LIBERATOR_AG, libPos, 1)) {
//                        Unit lib = unsiegedLibs.remove(0);
//                        float offset = Base.getLibDistanceFromCC() - 5;
//                        Point2d zonePos = Position.towards(base.getCcPos(), libPos, offset);
//                        Bot.ACTION.unitCommand(lib, Abilities.MOVE, libPos, false)
//                                .unitCommand(lib, Abilities.MORPH_LIBERATOR_AG_MODE, zonePos, true);
//                        break; //keep 2 libs from going to the same positions at the same time
//                    }
//                }
//            }
//        }
//
//        //TODO: be way smarter with extra libs than this blind siege up below
//        //TODO: change loop below when GameState.baseList tracks enemy bases too
//        if (!availableLibs.isEmpty()) {
//            //if all extra libs are at home with nowhere to go, siege the enemy bases
//            if (availableLibs.stream().allMatch(unit -> UnitUtils.getDistance(unit, ArmyManager.retreatPos) < 3)) {
//                //get list of enemy townhalls sorted by closest to me
//                List<UnitInPool> enemyCommandStructures = Bot.OBS.getUnits(Alliance.ENEMY, u -> UnitUtils.enemyCommandStructures.contains(u.unit().getType()));
//                enemyCommandStructures.sort(Comparator.comparing(u -> UnitUtils.getDistance(u.unit(), ArmyManager.retreatPos)));
//                //siege one liberator on each base
//                for (int i=0; i<enemyCommandStructures.size() && !availableLibs.isEmpty(); i++) {
//                    Bot.ACTION.unitCommand(availableLibs.remove(0), Abilities.MORPH_LIBERATOR_AG_MODE, enemyCommandStructures.get(i).unit().getPosition().toPoint2d(), false);
//                }
//                //a-move leftover libs to their death (enemy 3rd base, then nat, then main)
//                if (!availableLibs.isEmpty()) {
//                    Bot.ACTION.unitCommand(availableLibs, Abilities.ATTACK, LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-3), false)
//                            .unitCommand(availableLibs, Abilities.ATTACK, LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-2), true)
//                            .unitCommand(availableLibs, Abilities.ATTACK, LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-1), true);
//                }
//            }
//
//            //send left over free libs to main base
//            else {
//                Bot.ACTION.unitCommand(availableLibs, Abilities.ATTACK, ArmyManager.retreatPos, false);
//            }
//        }
//    }

    private static void positionLiberators() { //positions only 1 liberator per game loop
        Unit idleLib = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_LIBERATOR).stream()
                .filter(unit -> unit.getOrders().isEmpty())
                .findFirst().orElse(null);


        if (idleLib != null) {
            boolean isLibPlaced = false;

            //send available liberator to siege an expansion
            List<Base> allButEnemyStarterBases = GameCache.baseList.subList(0, GameCache.baseList.size()-BuildManager.getNumEnemyBasesIgnored());
            outer: for (Base base : allButEnemyStarterBases) {
                if (base.isMyBase() && !base.isMyMainBase() && !base.isDryedUp()) { //my expansion bases only
                    for (DefenseUnitPositions libPos : base.getLiberators()) {
                        if (libPos.getUnit().isEmpty()) {
                            libPos.setUnit(Bot.OBS.getUnit(idleLib.getTag()));
                            Bot.ACTION.unitCommand(idleLib, Abilities.MOVE, Position.towards(libPos.getPos(), base.getCcPos(), -2), false)
                                    .unitCommand(idleLib, Abilities.MORPH_LIBERATOR_AG_MODE, Position.towards(libPos.getPos(), base.getCcPos(), 5), true);
                            isLibPlaced = true;
                            break outer;
                        }
                    }
                }
            }

            //if nowhere to send lib and no expansions left, siege newest enemy base (or siege enemy 3rd base if no enemy bases are known)
            if (!isLibPlaced && allButEnemyStarterBases.stream().noneMatch(base -> base.isUntakenBase() && !base.isDryedUp())) {
                GameCache.baseList.stream()
                        .filter(base -> base.isEnemyBase)
                        .findFirst()
                        .ifPresentOrElse(base -> Bot.ACTION.unitCommand(idleLib, Abilities.MORPH_LIBERATOR_AG_MODE,
                                        Position.towards(base.getCcPos(), idleLib.getPosition().toPoint2d(), 1.7f), true),
                                () -> Bot.ACTION.unitCommand(idleLib, Abilities.MORPH_LIBERATOR_AG_MODE,
                                        Position.towards(GameCache.baseList.get(GameCache.baseList.size()-3).getCcPos(), idleLib.getPosition().toPoint2d(), 1.7f), true));
            }
        }
    }

    private static void positionTanks() { //positions only 1 tank per game loop
        //TODO: unsiege tanks on base.onEnemyBaseLost

        Unit idleTank = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_SIEGE_TANK).stream()
                .filter(unit -> unit.getOrders().isEmpty())
                .findFirst().orElse(null);


        if (idleTank != null) {
            boolean isTankPlaced = false;

            //send available tank to siege an expansion
            List<Base> allButEnemyStarterBases = GameCache.baseList.subList(0, GameCache.baseList.size()-BuildManager.getNumEnemyBasesIgnored());
            outer: for (Base base : allButEnemyStarterBases) {
                if (base.isMyBase() && !base.isMyMainBase() && !base.isDryedUp()) { //my expansion bases only
                    for (DefenseUnitPositions tankPos : base.getTanks()) {
                        if (tankPos.getUnit().isEmpty()) {
                            tankPos.setUnit(Bot.OBS.getUnit(idleTank.getTag()));
                            Bot.ACTION.unitCommand(idleTank, Abilities.ATTACK, tankPos.getPos(), false)
                                    .unitCommand(idleTank, Abilities.MORPH_SIEGE_MODE, true);
                            isTankPlaced = true;
                            break outer;
                        }
                    }
                }
            }

            //if nowhere to send tank and no expansions available, a-move tank to its death
            if (!isTankPlaced && allButEnemyStarterBases.stream().noneMatch(base -> base.isUntakenBase() && !base.isDryedUp())) {
                GameCache.baseList.stream()
                        .filter(base -> base.isEnemyBase)
                        .forEach(base -> Bot.ACTION.unitCommand(idleTank, Abilities.ATTACK, base.getCcPos(), true));
                Bot.ACTION.unitCommand(idleTank, Abilities.ATTACK, GameCache.baseList.get(GameCache.baseList.size()-1).getCcPos(), true);
            }
        }
    }

    private static void emptyBunker() {
        //if PF at natural
        if (GameCache.baseList.get(1).getCc().map(UnitInPool::unit).map(Unit::getType).orElse(null) == Units.TERRAN_PLANETARY_FORTRESS) {
            //salvage bunker
            List<Unit> bunkerList = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_BUNKER);
            if (!bunkerList.isEmpty()) {
                bunkerList.stream()
                        .filter(bunker -> UnitUtils.getDistance(bunker, LocationConstants.BUNKER_NATURAL) < 1)
                        .forEach(bunker -> {
                            Bot.ACTION.unitCommand(bunker, Abilities.UNLOAD_ALL_BUNKER, false); //rally is already set to top of inside main wall
                            Bot.ACTION.unitCommand(bunker, Abilities.EFFECT_SALVAGE, false);
                        });
            }
        }
    }

    private static void raiseAndLowerDepots() {
        for(Unit depot : UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_SUPPLY_DEPOT)) {
            Point2d depotPos = depot.getPosition().toPoint2d();
            if (!InfluenceMaps.getValue(InfluenceMaps.pointRaiseDepots, depotPos)) {
                Bot.ACTION.unitCommand(depot, Abilities.MORPH_SUPPLY_DEPOT_LOWER, false);
            }
        }
        for(Unit depot : UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_SUPPLY_DEPOT_LOWERED)) {
            Point2d depotPos = depot.getPosition().toPoint2d();
            if (InfluenceMaps.getValue(InfluenceMaps.pointRaiseDepots, depotPos) && CannonRushDefense.cannonRushStep == 0) {
                Bot.ACTION.unitCommand(depot, Abilities.MORPH_SUPPLY_DEPOT_RAISE, false);
            }
        }
    }

    public static void spreadArmy(List<Unit> army) {
        for (Unit unit : army) {
            if (unit.getOrders().isEmpty()) {
                Bot.ACTION.unitCommand(unit, Abilities.ATTACK, Bot.OBS.getGameInfo().findRandomLocation(), false);
            }
        }
    }

    private static Point2d calculateTankPosition(Point2d ccPos) {//pick position away from enemy main base like a knight move (3x1)
        float xCC = ccPos.getX();
        float yCC = ccPos.getY();
        float xEnemy = LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-1).getX();
        float yEnemy = LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-1).getY();
        float xDistance = xEnemy - xCC;
        float yDistance = yEnemy - yCC;
        float xMove = 2;
        float yMove = 2;

        if (Math.abs(xDistance) > Math.abs(yDistance)) { //move 3x1
            xMove = 5f;
        }
        else { //move 1x3
            yMove = 5f;
        }
        if (xDistance > 0) {
            xMove *= -1;
        }
        if (yDistance > 0) {
            yMove *= -1;
        }
        return Point2d.of(xCC + xMove, yCC + yMove);
    }

    public static int calcNumVikingsNeeded() {
        float answer = 0;
        boolean hasDetector = false;
        boolean hasTempests = false;
        for (UnitInPool enemy : GameCache.allEnemiesList) {
            switch ((Units)enemy.unit().getType()) {
                case TERRAN_RAVEN: case ZERG_OVERSEER: case PROTOSS_OBSERVER:
                    hasDetector = true;
                    break;
                case TERRAN_VIKING_FIGHTER: case TERRAN_VIKING_ASSAULT:
                    answer += 1.5;
                    break;
                case TERRAN_LIBERATOR: case TERRAN_LIBERATOR_AG: case TERRAN_BANSHEE:
                case ZERG_CORRUPTOR: case ZERG_MUTALISK: case ZERG_VIPER: case ZERG_BROODLORD_COCOON: case ZERG_BROODLORD:
                case PROTOSS_ORACLE:
                    answer += 1;
                    break;
                case PROTOSS_PHOENIX:
                    answer += 3;
                    break;
                case PROTOSS_VOIDRAY:
                    answer += 1.5;
                    break;
                case TERRAN_BATTLECRUISER: case PROTOSS_CARRIER:
                    answer += 3.67;
                    break;
                case PROTOSS_TEMPEST:
                    hasTempests = true;
                    answer += 2;
                    break;
                case PROTOSS_MOTHERSHIP:
                    answer += 4;
                    break;
            }
        }
        if (hasTempests) { //minimum 10 vikings at all times if enemy has a tempest
            answer = Math.max(10, answer);
        }
        else if (Switches.enemyCanProduceAir) { //set minimum vikings if enemy can produce air
            if (LocationConstants.opponentRace == Race.PROTOSS) {
                answer = Math.max(6, answer);
            }
            else {
                answer = Math.max(2, answer);
            }
        }
        else if (hasDetector && Bot.OBS.getUpgrades().contains(Upgrades.BANSHEE_CLOAK) && UnitUtils.getNumFriendlyUnits(Units.TERRAN_BANSHEE, true) > 0) {
            answer = Math.max((LocationConstants.opponentRace == Race.PROTOSS) ? 2 : 3, answer); //minimum vikings if he has a detector
        }
        answer = Math.max(answer, GameCache.bansheeList.size() / 5); //at least 1 safety viking for every 5 banshees
        return (int)answer;
    }

    public static void giveBansheeCommand(Unit banshee) {
        ArmyCommands lastCommand = getCurrentCommand(banshee);
        int x = InfluenceMaps.toMapCoord(banshee.getPosition().getX());
        int y = InfluenceMaps.toMapCoord(banshee.getPosition().getY());
        boolean isUnsafe = (Switches.isDivingTempests) ? false : InfluenceMaps.pointThreatToAir[x][y] > 2;
        boolean canRepair = !Cost.isGasBroke() && !Cost.isMineralBroke();
        boolean isInDetectionRange = InfluenceMaps.pointDetected[x][y];
        boolean isInBansheeRange = InfluenceMaps.pointInBansheeRange[x][y];
        boolean canAttack = banshee.getWeaponCooldown().orElse(1f) == 0f && InfluenceMaps.pointThreatToAir[x][y] < 200;
        CloakState cloakState = banshee.getCloakState().orElse(CloakState.NOT_CLOAKED);
        boolean canCloak = banshee.getEnergy().orElse(0f) > Strategy.ENERGY_BEFORE_CLOAKING &&
                Bot.OBS.getUpgrades().contains(Upgrades.BANSHEE_CLOAK);
        boolean isParasitic = banshee.getBuffs().contains(Buffs.PARASITIC_BOMB); //TODO: parasitic bomb run sideways
        boolean isDecloakBuffed = UnitUtils.hasDecloakBuff(banshee);
        boolean isHomeUnderAttack = !defensePos.equals(retreatPos);

        //always flee if locked on by cyclone
        if (banshee.getBuffs().contains(Buffs.LOCK_ON)) {
            if (!isInDetectionRange && canCloak && !isDecloakBuffed) {
                Bot.ACTION.unitCommand(banshee, Abilities.BEHAVIOR_CLOAK_ON_BANSHEE, false);
            }
            else {
                if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
            }
        }
        //shoot when available
        else if (canAttack && isInBansheeRange) {
            //attack
            if (lastCommand != ArmyCommands.ATTACK) armyGroundAttacking.add(banshee);
        }
        //fly to enemy main if parasitic'ed
        else if (isParasitic) {
            Bot.ACTION.unitCommand(banshee, Abilities.MOVE, LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-1), false);
        }
        //staying in repair bay if not full health and if needing energy
        else if (canRepair && //not broke
                UnitUtils.getDistance(banshee, retreatPos) < 3 && //at repair bay
                UnitUtils.getHealthPercentage(banshee) < 100) { //wait for heal
            if (cloakState == CloakState.CLOAKED_ALLIED && !isUnsafe) {
                Bot.ACTION.unitCommand(banshee, Abilities.BEHAVIOR_CLOAK_OFF_BANSHEE, false);
            }
            else {
                if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
            }
        }
        //go home if low health
        else if (canRepair && UnitUtils.getHealthPercentage(banshee) < Strategy.RETREAT_HEALTH) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
        }

        else if (isUnsafe) {
            if (isInDetectionRange) {
                //retreat
                if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
            }
            else if (cloakState == CloakState.CLOAKED_ALLIED &&
                    banshee.getEnergy().get() > 3 + ((UnitUtils.getEnemyUnitsOfType(Units.PROTOSS_TEMPEST).size() > 2) ? 2 : 0)) { //additional energy for time to flee tempest range
                if (isInBansheeRange) {
                    //retreat
                    if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
                }
                else {
                    //attack
                    if (lastCommand != ArmyCommands.ATTACK) armyGroundAttacking.add(banshee);
                }
            }
            else if (canCloak && !isDecloakBuffed) {
                //cloak
                Bot.ACTION.unitCommand(banshee, Abilities.BEHAVIOR_CLOAK_ON_BANSHEE, false);
            }
            else {
                //retreat
                if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
            }
        }
        //go to repair bay when waiting on cloak, and not needed for defense
        else if (isWaitingForCloak(canCloak, cloakState) && !isHomeUnderAttack) {
            //retreat
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
        }
        else {
            if (isInBansheeRange) {
                //retreat
                if (lastCommand != ArmyCommands.HOME) armyRetreating.add(banshee);
            }
            else {
                //attack
                if (lastCommand != ArmyCommands.ATTACK) armyGroundAttacking.add(banshee);
            }
        }
    }

    private static boolean isWaitingForCloak(boolean canCloak, CloakState cloakState) {
        return !Strategy.MASS_RAVENS && !canCloak && cloakState != CloakState.CLOAKED_ALLIED;
        //not mass ravens AND can't cloak AND not cloaked
    }

    private static ArmyCommands getCurrentCommand(Unit unit) {
        ArmyCommands currentCommand = ArmyCommands.EMPTY;
        if (!unit.getOrders().isEmpty()) {
            UnitOrder order = unit.getOrders().get(0);
            if (order.getAbility() == Abilities.ATTACK) {
                if (order.getTargetedWorldSpacePosition().isPresent() &&
                        order.getTargetedWorldSpacePosition().get().toPoint2d().distance(attackGroundPos) < 1) {
                    currentCommand = ArmyCommands.ATTACK;
                }
                else if (order.getTargetedUnitTag().isPresent()) {
                    currentCommand = ArmyCommands.DIVE;
                }
            }
            else if (order.getAbility() == Abilities.MOVE &&
                    order.getTargetedWorldSpacePosition().isPresent() &&
                    order.getTargetedWorldSpacePosition().get().toPoint2d().distance(ArmyManager.retreatPos) < 1) {
                currentCommand = ArmyCommands.HOME;
            }
        }
        return currentCommand;
    }

    private static void giveVikingCommand(Unit viking) { //never kites outside of range of air units... always engages maintaining max range TODO: change this for tempests
        ArmyCommands lastCommand = getCurrentCommand(viking);
        int x = InfluenceMaps.toMapCoord(viking.getPosition().getX());
        int y = InfluenceMaps.toMapCoord(viking.getPosition().getY());
        boolean isUnsafe = InfluenceMaps.pointThreatToAirFromGround[x][y] > 0;
        boolean canRepair = !Cost.isGasBroke() && !Cost.isMineralBroke();

        //keep vikings back if tempests are on the map, but no other toss air units are visible TODO: include outnumbered viking count too???
        if (!UnitUtils.getEnemyUnitsOfType(Units.PROTOSS_TEMPEST).isEmpty() && //TODO: change below to use Gamestate.allVisibleEnemiesMap
                Bot.OBS.getUnits(Alliance.ENEMY,
                        e -> (e.unit().getType() == Units.PROTOSS_PHOENIX || e.unit().getType() == Units.PROTOSS_VOIDRAY || e.unit().getType() == Units.PROTOSS_INTERCEPTOR)
                        && !e.unit().getHallucination().orElse(false)).isEmpty()) {
            isUnsafe = InfluenceMaps.pointVikingsStayBack[x][y];
        }
        else if (GameCache.vikingList.size() <= UnitUtils.getEnemyUnitsOfType(Units.TERRAN_VIKING_FIGHTER).size()) {
            isUnsafe = InfluenceMaps.pointThreatToAir[x][y] > 0;
        }

        boolean isInVikingRange = InfluenceMaps.pointInVikingRange[x][y];
        boolean canAttack = viking.getWeaponCooldown().orElse(1f) == 0f;
        boolean isParasitic = viking.getBuffs().contains(Buffs.PARASITIC_BOMB); //TODO: parasitic bomb run sideways

        //always flee if locked on by cyclone
        if (viking.getBuffs().contains(Buffs.LOCK_ON)) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(viking);
        }
        //shoot when available
        else if (canAttack && isInVikingRange) {
            //attack
            if (lastCommand != ArmyCommands.ATTACK) armyAirAttacking.add(viking);
        }
        //fly to enemy main if parasitic'ed
        else if (isParasitic) {
            Bot.ACTION.unitCommand(viking, Abilities.MOVE, LocationConstants.baseLocations.get(LocationConstants.baseLocations.size()-1), false);
        }
        //Under 100% health and at repair bay
        else if (canRepair &&
                UnitUtils.getHealthPercentage(viking) < 100 &&
                viking.getPosition().toPoint2d().distance(retreatPos) < 3) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(viking);
        }
        //go home if there are no air threats and viking under 100% health
        else if (!Strategy.enemyHasAirThreat && viking.getHealth().get() < viking.getHealthMax().get()) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(viking);
        }
        //go home if low health
        else if (canRepair && UnitUtils.getHealthPercentage(viking) < Strategy.RETREAT_HEALTH) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(viking);
        }
        //in range then back up
        else if (isUnsafe || isInVikingRange) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(viking);
        }
        //out of range, then move in
        else {
            if (lastCommand != ArmyCommands.ATTACK) armyAirAttacking.add(viking);
        }
    }

    //return true if autoturret cast
    private static void giveRavenCommand(Unit raven, boolean doCastTurrets) {
        //wait for raven to auto-turret before giving a new command
        if (!raven.getOrders().isEmpty() && raven.getOrders().get(0).getAbility() == Abilities.EFFECT_AUTO_TURRET) {
            return;
        }

        ArmyCommands lastCommand = getCurrentCommand(raven);
        boolean isUnsafe = (raven.getEnergy().orElse(0f) >= Strategy.AUTOTURRET_AT_ENERGY)
                ? InfluenceMaps.getValue(InfluenceMaps.pointThreatToAir, raven.getPosition().toPoint2d()) > 0
                : InfluenceMaps.getValue(InfluenceMaps.pointThreatToAirPlusBuffer, raven.getPosition().toPoint2d());
        boolean inRange = InfluenceMaps.getValue(InfluenceMaps.pointInBansheeRange, raven.getPosition().toPoint2d());
        boolean canRepair = !Cost.isGasBroke() && !Cost.isMineralBroke();

        //always flee if locked on by cyclone
        if (raven.getBuffs().contains(Buffs.LOCK_ON)) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(raven);
        }

        //stay in repair bay not on offensive and until 100% health
        else if (canRepair && UnitUtils.getHealthPercentage(raven) < 100 && raven.getPosition().toPoint2d().distance(retreatPos) < 3) {
            if (lastCommand != ArmyCommands.HOME) armyRetreating.add(raven);
        }

        //go home to repair if low
        else if (canRepair && UnitUtils.getHealthPercentage(raven) < Strategy.RETREAT_HEALTH) {
            if (!doCastTurrets || !doAutoTurretOnRetreat(raven)) {
                if (lastCommand != ArmyCommands.HOME) armyRetreating.add(raven);
            }
        }
        //back up if in range
        else if (isUnsafe || inRange) {
            if (!Strategy.DO_SEEKER_MISSILE || !castSeeker(raven)) {
                if (!doCastTurrets || !doAutoTurret(raven)) {
                    if (isUnsafe) {
                        if (lastCommand != ArmyCommands.HOME) {
                            armyRetreating.add(raven);
                        }
                    }
                    else if (lastCommand != ArmyCommands.ATTACK) {
                        armyGroundAttacking.add(raven);
                    }
                }
            }
        }
        //go forward if not in range
        else if (lastCommand != ArmyCommands.ATTACK) {
            armyGroundAttacking.add(raven);
        }
    }

    //drop auto-turrets near enemy before going home to repair
    private static boolean doAutoTurretOnRetreat(Unit raven) {
        if (raven.getEnergy().orElse(0f) >= 50 && UnitUtils.getDistance(raven, attackGroundPos) < 12 && attackUnit != null) {
            return castAutoTurret(raven, false);
        }
        return false;
    }

    //drop auto-turrets near enemy when max energy
    private static boolean doAutoTurret(Unit raven) {
        if (raven.getEnergy().orElse(0f) >= Strategy.AUTOTURRET_AT_ENERGY) {
            return castAutoTurret(raven, true);
        }
        return false;
    }

    private static boolean castAutoTurret(Unit raven, boolean useForwardPosition) {
        float castRange = (useForwardPosition) ? 2f : 0f;
        Point2d turretPos = Position.toWholePoint(
                Position.towards(raven.getPosition().toPoint2d(), attackGroundPos, castRange));
        List<Point2d> posList = Position.getSpiralList(turretPos, 3).stream()
                .filter(p -> p.distance(attackGroundPos) < 8)
                .filter(p -> Bot.OBS.isPlacable(p))
                .sorted(Comparator.comparing(p -> p.distance(attackGroundPos)))
                .collect(Collectors.toList());
        if (posList.isEmpty()) {
            return false;
        }

        List<QueryBuildingPlacement> queryList = posList.stream()
                .map(p -> QueryBuildingPlacement
                        .placeBuilding()
                        .useAbility(Abilities.EFFECT_AUTO_TURRET)
                        .on(p).build())
                .collect(Collectors.toList());

        List<Boolean> placementList = Bot.QUERY.placement(queryList);
        queriesMade++;

        if (placementList.contains(true)) {
//            int best = 0;
//            for (int i=0; i<posList.size(); i++) {
//                if (placementList.get(i)) {
//                    best++;
//                    DebugHelper.drawBox(posList.get(i), Color.GREEN, 0.5f);
//                    DebugHelper.drawText(String.valueOf(best), posList.get(i), Color.GREEN);
//                }
//            }
//            Bot.DEBUG.sendDebug();

            Point2d placementPos = posList.get(placementList.indexOf(true));
            Bot.ACTION.unitCommand(raven, Abilities.EFFECT_AUTO_TURRET, placementPos, false);
            turretsCast++;
            return true;
        }
        return false;
    }

    private static boolean castSeeker(Unit raven) {
        //cast seeker only once every 3sec
        if (Bot.OBS.getGameLoop() < prevSeekerFrame + 70) {
            return false;
        }
        float ravenEnergy = raven.getEnergy().orElse(0f);
        if (ravenEnergy >= 75) {
            Point2d targetPos = findSeekerTarget((int)raven.getPosition().getX(), (int)raven.getPosition().getY(), (ravenEnergy > 150));  //UnitUtils.towards(raven.getPosition().toPoint2d(), attackPos, 8.5f);
            if (targetPos == null) {
                return false;
            }
            List<UnitInPool> targetUnitList = Bot.OBS.getUnits(Alliance.ENEMY, unit -> unit.unit().getPosition().toPoint2d().distance(targetPos) < 0.5f);
            if (targetUnitList.isEmpty()) {
                return false;
            }
            Bot.ACTION.unitCommand(raven, Abilities.EFFECT_ANTI_ARMOR_MISSILE, targetUnitList.get(0).unit(), false);
            prevSeekerFrame = Bot.OBS.getGameLoop();
            return true;
        }
        return false;
    }

    private static Point2d findSeekerTarget(int ravenX, int ravenY, boolean isMaxEnergy) {
        int bestX = 0; int bestY = 0; float bestValue = 0;
        int xMin = Math.max(0, ravenX-Strategy.CAST_SEEKER_RANGE);
        int xMax = Math.min(InfluenceMaps.toMapCoord(LocationConstants.SCREEN_TOP_RIGHT.getX()), (ravenX+Strategy.CAST_SEEKER_RANGE)*2);
        int yMin = Math.max(0, ravenY-Strategy.CAST_SEEKER_RANGE);
        int yMax = Math.min(InfluenceMaps.toMapCoord(LocationConstants.SCREEN_TOP_RIGHT.getY()), (ravenY+Strategy.CAST_SEEKER_RANGE)*2);
        for (int x=xMin; x<xMax; x++) {
            for (int y=yMin; y<yMax; y++) {
                if (InfluenceMaps.pointSupplyInSeekerRange[x][y] > bestValue) {
                    bestX = x;
                    bestY = y;
                    bestValue = InfluenceMaps.pointSupplyInSeekerRange[x][y];
                }
            }
        }
        float minSupplyToSeeker = (isMaxEnergy) ? Strategy.MIN_SUPPLY_TO_SEEKER - 7 : Strategy.MIN_SUPPLY_TO_SEEKER;
        return (bestValue < minSupplyToSeeker) ? null : Point2d.of(bestX/2f, bestY/2f);
    }

    public static boolean shouldDive(Units unitType, Unit enemy) {
        int numAttackersNearby = UnitUtils.getUnitsNearbyOfType(Alliance.SELF, unitType, enemy.getPosition().toPoint2d(), Strategy.DIVE_RANGE).size();
        if (numAttackersNearby < 2) {
            return false;
        }

        if (unitType == Units.TERRAN_VIKING_FIGHTER && UnitUtils.getEnemyUnitsOfType(Units.TERRAN_VIKING_FIGHTER).size() >= 6) {
            return false;
        }

        //calculate point from detector
        Point2d threatPoint = getPointFromA(enemy.getPosition().toPoint2d(), retreatPos, Bot.OBS.getUnitTypeData(false).get(unitType).getWeapons().iterator().next().getRange());

        int x = InfluenceMaps.toMapCoord(threatPoint.getX());
        int y = InfluenceMaps.toMapCoord(threatPoint.getY());

        //if 25%+ of the threat is from air units, don't dive vikings
//        if (unitType == Units.TERRAN_VIKING_FIGHTER && GameState.pointThreatToAirFromGround[x][y] < GameState.pointThreatToAir[x][y] * 0.75) {
//            return false;
//        }

        //calculate if I have enough units to dive in and snipe the detector
        return numAttackersNearby >= numNeededToDive(enemy, InfluenceMaps.pointThreatToAir[x][y]);
    }

    public static boolean shouldDiveTempests(Point2d closestTempest, int numVikingsNearby) {
        //if not enough vikings to deal with the tempests
        if (numVikingsNearby < Math.min(Strategy.MAX_VIKINGS_TO_DIVE_TEMPESTS, ArmyManager.calcNumVikingsNeeded() * 0.8)) {
            return false;
        }

        //if maxed, then send the vikings
        if (Bot.OBS.getFoodUsed() >= 197) {
            return true;
        }

        //check if too much support units
        //TODO: change to include stalkers out of vision??
        List<UnitInPool> aaThreats = Bot.OBS.getUnits(Alliance.ENEMY, u ->
                (u.unit().getType() == Units.PROTOSS_VOIDRAY || u.unit().getType() == Units.PROTOSS_STALKER ||
                        u.unit().getType() == Units.PROTOSS_INTERCEPTOR || u.unit().getType() == Units.PROTOSS_PHOENIX) &&
                        UnitUtils.getDistance(u.unit(), closestTempest) < 15);
        int threatTotal = 0;
        for (UnitInPool u : aaThreats) {
            Unit threat = u.unit();
            switch ((Units)threat.getType()) {
                case PROTOSS_VOIDRAY:
                    threatTotal += 4;
                    break;
                case PROTOSS_PHOENIX: case PROTOSS_STALKER:
                    threatTotal += 2;
                    break;
                case PROTOSS_INTERCEPTOR:
                    threatTotal += 1;
                    break;
            }
        }
        float ratio = (UnitUtils.getEnemyUnitsOfType(Units.PROTOSS_TEMPEST).size() < 3) ? 0.65f : 1.2f;
        return threatTotal < numVikingsNearby * ratio; //larger ratio = dive more frequently
    }

    private static int numNeededToDive(Unit enemy, int threatLevel) {
        float enemyHP = enemy.getHealth().orElse(60f) + enemy.getShield().orElse(0f); //60f to represent the 40hp +20shields of an observer
        if (enemy.getType() == Units.ZERG_OVERSEER) {
            enemyHP *= 0.71; //adjustment for +armored damage
        }
        threatLevel *= 1.3; //hack to be more scared of enemy aa, in general
        return Math.min(2, (int)((enemyHP*threatLevel+2500)/1500 + (enemyHP/500) + (threatLevel/20))) + 1;
    }

    public static Point2d getPointFromA(Point2d a, Point2d b, float distance) {
        double ratio = distance / a.distance(b);
        int newX = (int)(((b.getX() - a.getX()) * ratio) + a.getX());
        int newY = (int)(((b.getY() - a.getY()) * ratio) + a.getY());
        return Point2d.of(newX, newY);
    }

    public static Point2d getNextRavenPosition(Point2d curPosition, boolean isLeft, boolean isAttacking) {
        boolean moveClockwise = (isLeft && isAttacking) || (!isLeft && !isAttacking);
        int x = (int)curPosition.getX();
        int y = (int)curPosition.getY();

        //top left corner
        if (y == LocationConstants.MAX_Y && x == 0) {
            return (moveClockwise) ? Point2d.of(LocationConstants.MAX_X, LocationConstants.MAX_Y) : Point2d.of(0, 0);
        }
        //top right corner
        if (y == LocationConstants.MAX_Y && x == LocationConstants.MAX_X) {
            return (moveClockwise) ? Point2d.of(LocationConstants.MAX_X, 0) : Point2d.of(0, LocationConstants.MAX_Y);
        }
        //bottom left corner
        if (y == 0 && x == 0) {
            return (moveClockwise) ? Point2d.of(0, LocationConstants.MAX_Y) : Point2d.of(LocationConstants.MAX_X, 0);
        }
        //bottom right corner
        if (y == 0 && x == LocationConstants.MAX_X) {
            return (moveClockwise) ? Point2d.of(0, 0) : Point2d.of(LocationConstants.MAX_X, LocationConstants.MAX_Y);
        }
        //along bottom
        else if (y == 0) {
            return (moveClockwise) ? Point2d.of(0, y) : Point2d.of(LocationConstants.MAX_X, y);
        }
        //along top
        else if (y == LocationConstants.MAX_Y) {
            return (moveClockwise) ? Point2d.of(LocationConstants.MAX_X, y) : Point2d.of(0, y);
        }
        //along left
        else if (x == 0) {
            return (moveClockwise) ? Point2d.of(x, LocationConstants.MAX_Y) : Point2d.of(x, 0);
        }
        //along right
        else if (x == LocationConstants.MAX_X) {
            return (moveClockwise) ? Point2d.of(x, 0) : Point2d.of(x, LocationConstants.MAX_Y);
        }
        //out from edge
        else {
            //return nearest edge
            float xDistanceFromEdge = x;
            int newX = 0;
            float yDistanceFromEdge = y;
            int newY = 0;

            if (yDistanceFromEdge > LocationConstants.MAX_Y - y) {
                yDistanceFromEdge = LocationConstants.MAX_Y - y;
                newY = LocationConstants.MAX_Y;
            }
            if (xDistanceFromEdge > LocationConstants.MAX_X - x) {
                xDistanceFromEdge = LocationConstants.MAX_X - x;
                newX = LocationConstants.MAX_X;
            }
            return (xDistanceFromEdge < yDistanceFromEdge) ? Point2d.of(newX, y) : Point2d.of(x, newY);
        }
    }

    public static void sendBioProtection(Point2d expansionPos) {
        List<Unit> bio = UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MARINE);
        bio.addAll(UnitUtils.getFriendlyUnitsOfType(Units.TERRAN_MARAUDER));
        if (!bio.isEmpty()) {
            Bot.ACTION.unitCommand(bio, Abilities.ATTACK, expansionPos, true);
        }
    }
}
