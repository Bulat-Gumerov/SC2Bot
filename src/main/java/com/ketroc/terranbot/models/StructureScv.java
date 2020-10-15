package com.ketroc.terranbot.models;


import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.game.Race;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.ketroc.terranbot.*;
import com.ketroc.terranbot.bots.BansheeBot;
import com.ketroc.terranbot.bots.Bot;
import com.ketroc.terranbot.managers.WorkerManager;
import com.ketroc.terranbot.purchases.PurchaseStructure;

import java.util.ArrayList;
import java.util.List;

public class StructureScv {
    public static final List<StructureScv> scvBuildingList = new ArrayList<>();

    public Point2d structurePos;
    public boolean isGas;
    public UnitInPool gasGeyser;
    public Abilities buildAbility;
    public Units structureType;
    private UnitInPool scv;
    private UnitInPool structureUnit;
    public long scvAddedFrame;

    // *********************************
    // ********* CONSTRUCTORS **********
    // *********************************

    public StructureScv(UnitInPool scv, Abilities buildAbility, Point2d structurePos) {
        this.scv = scv;
        this.buildAbility = buildAbility;
        this.structureType = Bot.abilityToUnitType.get(buildAbility);
        this.structurePos = structurePos;
        scvAddedFrame = Bot.OBS.getGameLoop();
    }

    public StructureScv(UnitInPool scv, Abilities buildAbility, UnitInPool gasGeyser) {
        this.scv = scv;
        this.buildAbility = buildAbility;
        this.structureType = Bot.abilityToUnitType.get(buildAbility);
        this.isGas = true;
        this.gasGeyser = gasGeyser;
        this.structurePos = gasGeyser.unit().getPosition().toPoint2d();
        scvAddedFrame = Bot.OBS.getGameLoop();
    }

    // **************************************
    // ******** GETTERS AND SETTERS *********
    // **************************************

    public UnitInPool getScv() {
        return scv;
    }

    public void setScv(UnitInPool scv) {
        if (this.scv != null) {
            Ignored.remove(scv.getTag());
        }
        this.scv = scv;
        scvAddedFrame = Bot.OBS.getGameLoop();
        Ignored.add(new IgnoredUnit(scv.getTag()));
    }

    public UnitInPool getStructureUnit() {
        if (structureUnit == null) {
            List<UnitInPool> structure = UnitUtils.getUnitsNearbyOfType(Alliance.SELF, structureType, structurePos, 1);
            if (!structure.isEmpty()) {
                structureUnit = structure.get(0);
            }
        }
        return structureUnit;
    }

    public void setStructureUnit(UnitInPool structureUnit) {
        this.structureUnit = structureUnit;
    }

    // **************************
    // ******** METHODS *********
    // **************************

    public void cancelProduction() {
        //cancel structure
        if (getStructureUnit() != null && getStructureUnit().isAlive()) {
            Bot.ACTION.unitCommand(getStructureUnit().unit(), Abilities.CANCEL_BUILD_IN_PROGRESS, false);
        }

        //send scv to mineral patch
        if (scv.isAlive()) {
            Ignored.remove(scv.getTag());
            Bot.ACTION.unitCommand(scv.unit(), Abilities.STOP, false);
        }
    }

    @Override
    public String toString() {
        StringBuffer strBuff = new StringBuffer();
        strBuff.append("structurePos: ").append(structurePos)
                .append("\nstructureType: ").append(structureType)
                .append("\nscv.position: ").append(scv.unit().getPosition().toPoint2d())
                .append("\nscvAddedFrame: ").append(scvAddedFrame);
        return strBuff.toString();
    }

    // *********************************
    // ******** STATIC METHODS *********
    // *********************************

    public static boolean removeScvFromList(Unit structure) {
        for (int i = 0; i< scvBuildingList.size(); i++) {
            StructureScv scv = scvBuildingList.get(i);

            //hack to handle rich refineries
            Units structureType = (Units)structure.getType();
            if (structureType == Units.TERRAN_REFINERY_RICH) {
                structureType = Units.TERRAN_REFINERY;
            }

            if (scv.structureType == structureType && scv.structurePos.distance(structure.getPosition().toPoint2d()) < 1) {
                remove(scv);
                return true;
            }
        }
        return false;
    }

    //cancel structure that's already started
    //send scv to mineral patch
    //remove StructureScv from scvBuildingList
    public static boolean cancelProduction(Units type, Point2d pos) {
        for (int i = 0; i< scvBuildingList.size(); i++) {
            StructureScv scv = scvBuildingList.get(i);
            if (scv.structureType == type && scv.structurePos.distance(pos) < 1) {
                //cancel structure
                scv.cancelProduction();

                //remove StructureScv object from list
                remove(scv);
                return true;
            }
        }
        return false;
    }

    public static void checkScvsActivelyBuilding() {
        for (int i = 0; i< scvBuildingList.size(); i++) {
            StructureScv structureScv = scvBuildingList.get(i);

            //if assigned scv is dead add another
            if (!structureScv.scv.isAlive()) {
                List<UnitInPool> availableScvs = WorkerManager.getAvailableScvs(structureScv.structurePos);
                if (!availableScvs.isEmpty()) {

                    structureScv.setScv(availableScvs.get(0));
                }
            }

            //if scv doesn't have the build command
            if (structureScv.scv.unit().getOrders().isEmpty() || !structureScv.scv.unit().getOrders().stream().anyMatch(order -> order.getAbility() == structureScv.buildAbility)) {
                UnitInPool structure = structureScv.getStructureUnit();

                //if structure never started/destroyed, repurchase
                if (structure == null || !structure.isAlive()) {

                    //if cc location is blocked by burrowed unit or creep, set baseIndex to this base TODO: this will probably get replaced
                    if (LocationConstants.opponentRace == Race.ZERG &&
                            structureScv.scv.isAlive() &&
                            structureScv.structureType == Units.TERRAN_COMMAND_CENTER &&
                            UnitUtils.getDistance(structureScv.scv.unit(), structureScv.structurePos) < 5) {
                        int blockedBaseIndex = LocationConstants.baseLocations.indexOf(structureScv.structurePos);
                        if (blockedBaseIndex > 0) {
                            LocationConstants.baseAttackIndex = blockedBaseIndex;
                            System.out.println("blocked base.  set baseIndex to " + blockedBaseIndex);
                        }
                    }

                    //if under threat, requeue
                    if (InfluenceMaps.getValue(InfluenceMaps.pointThreatToGround, structureScv.structurePos) > 0 ||
                            UnitUtils.isWallUnderAttack()) {
                        requeueCancelledStructure(structureScv);
                        remove(structureScv);
                        i--;
                    }
                    else {
                        Cost.updateBank(structureScv.structureType);
                        if (Bot.QUERY.placement(structureScv.buildAbility, structureScv.structurePos)) {
                            Bot.ACTION.unitCommand(structureScv.scv.unit(), structureScv.buildAbility, structureScv.structurePos, false);
                        }
                    }
                }
                //if structure started but not complete
                else if (structure.unit().getBuildProgress() < 1.0f) {

                    //remove if there is a duplicate entry in scvBuildList
                    if (isDuplicateStructureScv(structureScv)) {
                        scvBuildingList.remove(i--);
                    }

                    //send another scv
                    else {
                        Bot.ACTION.unitCommand(structureScv.scv.unit(), Abilities.SMART, structure.unit(), false);
                    }
                }

                //if structure completed
                else if (structure.unit().getBuildProgress() == 1.0f) {
                    remove(structureScv);
                    i--;
                }
            }
        }
    }

    private static void requeueCancelledStructure(StructureScv structureScv) {
        switch (structureScv.structureType) {
            //don't queue rebuild on these structure types
            case TERRAN_COMMAND_CENTER:
            case TERRAN_REFINERY: case TERRAN_REFINERY_RICH:
            case TERRAN_BUNKER:
                break;
            case TERRAN_SUPPLY_DEPOT:
                LocationConstants.extraDepots.add(structureScv.structurePos);
                break;
            case TERRAN_STARPORT:
                LocationConstants.STARPORTS.add(structureScv.structurePos);
                break;
            case TERRAN_BARRACKS:
                if (LocationConstants.proxyBarracksPos == null || structureScv.structurePos.distance(LocationConstants.proxyBarracksPos) > 10) {
                    LocationConstants._3x3Structures.add(structureScv.structurePos);
                }
                BansheeBot.purchaseQueue.addFirst(new PurchaseStructure(structureScv.structureType));
                break;
            case TERRAN_ARMORY: case TERRAN_ENGINEERING_BAY: case TERRAN_GHOST_ACADEMY:
                LocationConstants._3x3Structures.add(structureScv.structurePos);
                BansheeBot.purchaseQueue.addFirst(new PurchaseStructure(structureScv.structureType));
                break;
            default:
                BansheeBot.purchaseQueue.addFirst(new PurchaseStructure(structureScv.structureType, structureScv.structurePos));
                break;
        }
    }

    public static boolean isAlreadyInProductionAt(Units type, Point2d pos) {
        return scvBuildingList.stream()
                .anyMatch(scv -> scv.structureType == type && scv.structurePos == pos);
    }

    public static boolean isAlreadyInProduction(Units type) {
        return scvBuildingList.stream()
                .anyMatch(scv -> scv.structureType == type);
    }

    //checks if an scv is within the scvBuildingList
    public static boolean isScvProducing(Unit scv) {
        return scvBuildingList.stream()
                .anyMatch(structureScv -> structureScv.scv.getTag().equals(scv.getTag()));
    }


    //check if another scv is already doing the assigned structure build
    private static boolean isDuplicateStructureScv(StructureScv structureScv) {
        for (StructureScv otherScv : scvBuildingList) {
            if (!structureScv.equals(otherScv) && otherScv.buildAbility == structureScv.buildAbility && UnitUtils.getDistance(otherScv.scv.unit(), structureScv.getStructureUnit().unit()) < 3) {
                return true;
            }
        }
        return false;
    }

    public static StructureScv findByScvTag(Tag scvTag) {
        return scvBuildingList.stream()
                .filter(structureScv -> structureScv.scv.getTag().equals(scvTag))
                .findFirst()
                .orElse(null);
    }


    public static void add(StructureScv structureScv) {
        scvBuildingList.add(structureScv);
        if (structureScv.scv != null) {
            Ignored.add(new IgnoredUnit(structureScv.scv.getTag()));
        }
    }

    public static void remove(StructureScv structureScv) {
        scvBuildingList.remove(structureScv);
        if (structureScv.scv != null) {
            Ignored.remove(structureScv.scv.getTag());
        }
    }
}
