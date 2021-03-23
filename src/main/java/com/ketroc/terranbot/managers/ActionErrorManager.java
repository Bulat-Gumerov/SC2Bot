package com.ketroc.terranbot.managers;

import com.github.ocraft.s2client.bot.gateway.UnitInPool;
import com.github.ocraft.s2client.protocol.action.ActionError;
import com.github.ocraft.s2client.protocol.action.ActionResult;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.CloakState;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.ketroc.terranbot.bots.Bot;
import com.ketroc.terranbot.micro.ExpansionClearing;
import com.ketroc.terranbot.models.StructureScv;
import com.ketroc.terranbot.utils.Print;
import com.ketroc.terranbot.utils.Time;
import com.ketroc.terranbot.utils.UnitUtils;

import java.util.ArrayList;
import java.util.List;

public class ActionErrorManager {
    public static List<ActionError> actionErrorList = new ArrayList<>();

    public static void onStep() {
        for (ActionError warning : actionErrorList) {
            Abilities ability = (Abilities)warning.getAbility().orElse(Abilities.INVALID);
            ActionResult actionResult = warning.getActionResult();
            if (UnitUtils.BUILD_ABILITIES.contains(ability) || ability == Abilities.LAND_COMMAND_CENTER) {
                Units structureType = Bot.abilityToUnitType.get(ability);
                StructureScv structureScv = StructureScv.findByScvTag(warning.getUnitTag().get());
                if (structureScv == null) {
                    Print.print("structure not found for ability: " + ability);
                    continue;
                }
                Point2d pos = structureScv.structurePos;
                Unit scv = structureScv.getScv().unit();
                Print.print("Action Error.  Structure: " + structureType);
                Print.print("Structure Pos: " + pos + ".  Scv Pos: " + scv.getPosition().toPoint2d());

                //blocked by creep
                if (isBlockedByCreep(actionResult)) {
                    if (ability == Abilities.BUILD_COMMAND_CENTER || ability == Abilities.LAND_COMMAND_CENTER) {
                        ExpansionClearing.add(pos);
                    }
                }
                else if (isBlockedByUnit(actionResult)) {
                    //blocked by burrowed zerg unit
                    if ((ability == Abilities.BUILD_COMMAND_CENTER || ability == Abilities.LAND_COMMAND_CENTER) && // || ability == Abilities.BUILD_MISSILE_TURRET
                            numBlockingEnemyUnits(pos, Bot.abilityToUnitType.get(ability)) == 0) {
                        ExpansionClearing.add(pos);
                    }
                    else {
                        //TODO: do nothing?? check threat? (nothing = same scv keeps trying to build the structure)
                        int q=0;
                    }
                }
                else if (isPathBlocked(actionResult)) {
                    //blocked by enemy structure
                    if (numBlockingEnemyUnits(pos, Bot.abilityToUnitType.get(ability)) != 0) {
                        StructureScv.cancelProduction(structureType, pos);
                    }
                    //scv is trapped
                    else if (UnitUtils.isUnitTrapped(scv)) {
                        //TODO: add scv to trapped unit code
                        int q=0;
                    }
                    //scv got stuck en route, TODO: or location unreachable by any scv
                    else {
                        //switch scv
                        UnitInPool oneScv = WorkerManager.getOneScv();
                        if (oneScv != null) {
                            structureScv.setScv(oneScv);
                        }
                    }
                }
            }
        }
        actionErrorList.clear();
    }

    private static boolean isBlockedByCreep(ActionResult r) {
        return r == ActionResult.CANT_BUILD_TOO_CLOSE_TO_CREEP_SOURCE || r == ActionResult.CANT_LAND_TOO_CLOSE_TO_CREEP_SOURCE;
    }

    //TODO: enemy structure blocking == COULDN'T REACH TARGET
    private static boolean isBlockedByUnit(ActionResult r) {
        return r == ActionResult.CANT_BUILD_LOCATION_INVALID || r == ActionResult.CANT_LAND_LOCATION_INVALID;
    }

    private static boolean isPathBlocked(ActionResult r) {
        return r == ActionResult.COULDNT_REACH_TARGET;
    }

    private static int numBlockingEnemyUnits(Point2d structurePos, Units structureType) {
        return Bot.OBS.getUnits(Alliance.ENEMY, enemy ->
                UnitUtils.getDistance(enemy.unit(), structurePos) < UnitUtils.getStructureRadius(structureType)*1.6 &&
                !enemy.unit().getFlying().orElse(true) &&
                !enemy.unit().getType().toString().contains("BURROWED") &&
                enemy.unit().getCloakState().orElse(CloakState.CLOAKED) != CloakState.CLOAKED &&
                enemy.unit().getCloakState().orElse(CloakState.CLOAKED) != CloakState.CLOAKED_DETECTED).size();
    }

}
