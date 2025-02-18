package com.unciv.logic.automation.unit

import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

object AirUnitAutomation {

    fun automateFighter(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        val enemyAirUnitsInRange = tilesInRange
            .flatMap { it.airUnits.asSequence() }.filter { it.civ.isAtWarWith(unit.civ) }

        if (enemyAirUnitsInRange.any()) return // we need to be on standby in case they attack

        if (BattleHelper.tryAttackNearbyEnemy(unit)) return

        if (tryRelocateToCitiesWithEnemyNearBy(unit)) return

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else

        val citiesByNearbyAirUnits = pathsToCities.keys
            .groupBy { key ->
                key.getTilesInDistance(unit.getMaxMovementForAirUnits())
                    .count {
                        val firstAirUnit = it.airUnits.firstOrNull()
                        firstAirUnit != null && firstAirUnit.civ.isAtWarWith(unit.civ)
                    }
            }

        if (citiesByNearbyAirUnits.keys.any { it != 0 }) {
            val citiesWithMostNeedOfAirUnits = citiesByNearbyAirUnits.maxByOrNull { it.key }!!.value
            //todo: maybe group by size and choose highest priority within the same size turns
            val chosenCity = citiesWithMostNeedOfAirUnits.minByOrNull { pathsToCities.getValue(it).size }!! // city with min path = least turns to get there
            val firstStepInPath = pathsToCities.getValue(chosenCity).first()
            unit.movement.moveToTile(firstStepInPath)
            return
        }

        // no city needs fighters to defend, so let's attack stuff from the closest possible location
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)

    }

    fun automateBomber(unit: MapUnit) {
        if (BattleHelper.tryAttackNearbyEnemy(unit)) return

        if (tryRelocateToCitiesWithEnemyNearBy(unit)) return

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)
    }

    private fun tryMoveToCitiesToAerialAttackFrom(pathsToCities: HashMap<Tile, ArrayList<Tile>>, airUnit: MapUnit) {
        val citiesThatCanAttackFrom = pathsToCities.keys
            .filter { destinationCity ->
                destinationCity != airUnit.currentTile
                    && destinationCity.getTilesInDistance(airUnit.getRange())
                    .any { TargetHelper.containsAttackableEnemy(it, MapUnitCombatant(airUnit)) }
            }
        if (citiesThatCanAttackFrom.isEmpty()) return

        //todo: this logic looks similar to some parts of automateFighter, maybe pull out common code
        //todo: maybe group by size and choose highest priority within the same size turns
        val closestCityThatCanAttackFrom =
            citiesThatCanAttackFrom.minByOrNull { pathsToCities[it]!!.size }!!
        val firstStepInPath = pathsToCities[closestCityThatCanAttackFrom]!!.first()
        airUnit.movement.moveToTile(firstStepInPath)
    }

    fun automateNukes(unit: MapUnit) {
        if (!unit.civ.isAtWar()) return
        // We should *Almost* never want to nuke our own city, so don't consider it
        val tilesInRange = unit.currentTile.getTilesInDistanceRange(2..unit.getRange())
        var highestTileNukeValue = 0
        var tileToNuke: Tile? = null
        tilesInRange.forEach {
            val value = getNukeLocationValue(unit, it)
            if (value > highestTileNukeValue) {
                highestTileNukeValue = value
                tileToNuke = it
            }
        }
        if (highestTileNukeValue > 0) {
            Nuke.NUKE(MapUnitCombatant(unit), tileToNuke!!)
        }
        tryRelocateMissileToNearbyAttackableCities(unit)
    }

    /**
     * Ranks the tile to nuke based off of all tiles in it's blast radius
     * By default the value is -500 to prevent inefficient nuking.
     */
    private fun getNukeLocationValue(nuke: MapUnit, tile: Tile): Int {
        val civ = nuke.civ
        if (!Nuke.mayUseNuke(MapUnitCombatant(nuke), tile)) return Int.MIN_VALUE
        val blastRadius = nuke.getNukeBlastRadius()
        val tilesInBlastRadius = tile.getTilesInDistance(blastRadius)
        val civsInBlastRadius = tilesInBlastRadius.mapNotNull { it.getOwner() } +
            tilesInBlastRadius.mapNotNull { it.getFirstUnit()?.civ }

        // Don't nuke if it means we will be declaring war on someone!
        if (civsInBlastRadius.any { it != civ && !it.isAtWarWith(civ) }) return -100000
        // If there are no enemies to hit, don't nuke
        if (!civsInBlastRadius.any { it.isAtWarWith(civ) }) return -100000

        // Launching a Nuke uses resources, therefore don't launch it by default
        var explosionValue = -500

        // Returns either ourValue or thierValue depending on if the input Civ matches the Nuke's Civ
        fun evaluateCivValue(targetCiv: Civilization, ourValue: Int, theirValue: Int): Int {
            if (targetCiv == civ) // We are nuking something that we own!
                return ourValue
            return theirValue // We are nuking an enemy!
        }
        for (targetTile in tilesInBlastRadius) {
            // We can only account for visible units
            if (targetTile.isVisible(civ)) {
                for (targetUnit in targetTile.getUnits()) {
                    if (targetUnit.isInvisible(civ)) continue
                    // If we are nuking a unit at ground zero, it is more likely to be destroyed
                    val tileExplosionValue = if (targetTile == tile) 80 else 50
                    
                    if (targetUnit.isMilitary()) {
                        explosionValue += if (targetTile == tile) evaluateCivValue(targetUnit.civ, -200, tileExplosionValue)
                        else evaluateCivValue(targetUnit.civ, -150, 50)
                    } else if (targetUnit.isCivilian()) {
                        explosionValue += evaluateCivValue(targetUnit.civ, -100, tileExplosionValue / 2)
                    }
                }
            }
            // Never nuke our own Civ, don't nuke single enemy civs as well
            if (targetTile.isCityCenter()
                && !(targetTile.getCity()!!.health <= 50f
                    && targetTile.neighbors.any {it.militaryUnit?.civ == civ})) // Prefer not to nuke cities that we are about to take
                explosionValue += evaluateCivValue(targetTile.getCity()?.civ!!, -100000, 250)
            else if (targetTile.owningCity != null) {
                val owningCiv = targetTile.owningCity?.civ!!
                // If there is a tile to add fallout to there is a 50% chance it will get fallout
                if (!(tile.isWater || tile.isImpassible() || targetTile.terrainFeatures.any { it == "Fallout" }))
                    explosionValue += evaluateCivValue(owningCiv, -40, 10)
                // If there is an improvment to pillage
                if (targetTile.improvement != null && !targetTile.improvementIsPillaged)
                    explosionValue += evaluateCivValue(owningCiv, -40, 20)
            }
            // If the value is too low end the search early
            if (explosionValue < -1000) return explosionValue
        }
        return explosionValue
    }

    // This really needs to be changed, to have better targeting for missiles
    fun automateMissile(unit: MapUnit) {
        if (BattleHelper.tryAttackNearbyEnemy(unit)) return
        tryRelocateMissileToNearbyAttackableCities(unit)
    }

    private fun tryRelocateMissileToNearbyAttackableCities(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        val immediatelyReachableCities = tilesInRange
            .filter { unit.movement.canMoveTo(it) }

        for (city in immediatelyReachableCities) if (city.getTilesInDistance(unit.getRange())
                .any { it.isCityCenter() && it.getOwner()!!.isAtWarWith(unit.civ) }
        ) {
            unit.movement.moveToTile(city)
            return
        }

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)
    }

    private fun tryRelocateToCitiesWithEnemyNearBy(unit: MapUnit): Boolean {
        val immediatelyReachableCitiesAndCarriers = unit.currentTile
            .getTilesInDistance(unit.getMaxMovementForAirUnits()).filter { unit.movement.canMoveTo(it) }

        for (city in immediatelyReachableCitiesAndCarriers) {
            if (city.getTilesInDistance(unit.getRange())
                    .any {
                        it.isVisible(unit.civ) &&
                            TargetHelper.containsAttackableEnemy(it,MapUnitCombatant(unit))
                    }) {
                unit.movement.moveToTile(city)
                return true
            }
        }
        return false
    }
}
