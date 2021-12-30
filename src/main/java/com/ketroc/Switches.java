package com.ketroc;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.game.Race;
import com.ketroc.utils.LocationConstants;
import com.ketroc.utils.UnitUtils;

public class Switches {
    public static UnitInPool bansheeDiveTarget; //target to snipe for banshees (spore/turret/cannon)
    public static UnitInPool vikingDiveTarget; //target to snipe for viking (overseer/raven/observer)
    public static boolean isDivingTempests; // true is vikingDiveTarget is a tempest
    public static boolean hasCastOCSpellThisFrame;

    public static boolean fastDepotBarracksOpener; //hardcoded start to get bunker up in time for reaper rush

    public static boolean firstObserverSpotted;
    public static boolean firstTankSpotted;
    public static boolean scvRushComplete = true;
    public static boolean enemyCanProduceAir;
    public static boolean enemyHasCloakThreat; //enemy can produce cloaked/burrowed attack units
    public static boolean phoenixAreReal;

    public static boolean includeTanks;
    public static boolean scoutScanComplete = true; //TODO: testing - trying out no scout scan TvP
    public static int numScansToSave = 0;

    public static void onStep() {
        hasCastOCSpellThisFrame = false;

        //+2 scans saved for observers
        if (LocationConstants.opponentRace == Race.PROTOSS &&
                !firstObserverSpotted &&
                !UnitUtils.getEnemyUnitsOfType(UnitUtils.OBSERVER_TYPE).isEmpty()) {
            numScansToSave += 2;
            firstObserverSpotted = true;
        }

        //+2 scans saved for tanks
        if (LocationConstants.opponentRace == Race.TERRAN &&
                !firstTankSpotted &&
                !UnitUtils.getEnemyUnitsOfType(UnitUtils.SIEGE_TANK_TYPE).isEmpty()) {
            numScansToSave += 2;
            firstTankSpotted = true;
        }
    }
}
