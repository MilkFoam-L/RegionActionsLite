package kim.hhhhhy.regions.data

import kim.hhhhhy.regions.data.area.AreaActions
import kim.hhhhhy.regions.data.area.AreaPosition
import kim.hhhhhy.regions.data.area.AreaType
import kim.hhhhhy.regions.data.area.AreaType.*
import kim.hhhhhy.regions.utils.evalKether
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.console
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import taboolib.common5.mirrorNow
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import taboolib.module.lang.sendInfo
import java.util.concurrent.ConcurrentHashMap


data class AreaSettings(
    val position: AreaPosition,
    val actions: AreaActions,
    val tickPeriod: Long
) {
    companion object {
        @Config("areas.yml", autoReload = true)
        lateinit var areasConfig: Configuration
            private set

        private val areasData = ConcurrentHashMap<String, AreaSettings>()

        /**
         * player.name to id -> task
         */
        private val playerAreas = ConcurrentHashMap<Pair<String, String>, PlatformExecutor.PlatformTask>()

        fun reloadArea() {
            playerAreas.clear()
            areasData.clear()
            val section = areasConfig.getConfigurationSection("Areas") ?: return
            section.getKeys(false).forEach { id ->
                val root = section.getConfigurationSection(id)!!
                val position = AreaPosition(root.getConfigurationSection("position") ?: error("缺少坐标设置"))
                val actions = AreaActions(root.getConfigurationSection("actions"))
                val tickPeriod = root.getLong("tickPeriod", ConfigSettings.config.getLong("AreaSettings.TickAction", 20))
                areasData[id] = AreaSettings(position, actions, tickPeriod)
            }
            console().sendInfo("plugin-areas-reload")
        }

        fun getAreas(location: Location): List<String> {
            val x = location.x
            val y = location.y
            val z = location.z
            val worldName = location.world?.name

            return areasData.filter { area ->
                val pos = area.value.position
                val isSameWorld = pos.world == worldName
                val isXInRange = x in pos.xMin..pos.xMax || x in pos.xMax..pos.xMin
                val isYInRange = y in pos.yMin..pos.yMax || y in pos.yMax..pos.yMin
                val isZInRange = z in pos.zMin..pos.zMax || z in pos.zMax..pos.zMin

                isSameWorld && isXInRange && isYInRange && isZInRange
            }.map { it.key }
        }

        private fun runEnterAction(player: Player, id: String) {
            mirrorNow("RegionActionsLite:Actions:Enter") {
                val actions = areasData[id]!!.actions.enter
                if (ConfigSettings.baffleCache.hasNext("${player.name}-Enter-$id").not()) {
                    return@mirrorNow
                }
                actions?.evalKether(player)
                startTick(player, id)
            }
        }
        private fun runLeaveAction(player: Player, id: String) {
            mirrorNow("RegionActionsLite:Actions:Leave") {
                val actions = areasData[id]!!.actions.leave
                if (ConfigSettings.baffleCache.hasNext("${player.name}-Leave-$id").not()) {
                    return@mirrorNow
                }
                actions?.evalKether(player)
                stopTick(player, id)
            }
        }
        private fun runTickAction(player: Player, id: String) {
            mirrorNow("RegionActionsLite:Actions:Tick") {
                val actions = areasData[id]!!.actions.tick
                actions?.evalKether(player)
            }
        }

        private fun startTick(player: Player, id: String) {
            val period = areasData[id]!!.tickPeriod
            playerAreas[player.name to id] = submit(period = period) {
                runTickAction(player, id)
            }
        }

        private fun stopTick(player: Player, id: String) {
            playerAreas[player.name to id]?.cancel()
        }

        /**
         * 执行一次区域类型动作
         */
        fun runActions(player: Player, id: String, type: AreaType) {
            when (type) {
                ENTER -> {
                    runEnterAction(player, id)
                }
                LEAVE -> {
                    runLeaveAction(player, id)
                }
                TICK -> {
                    runTickAction(player, id)
                }
                ALL -> {
                    runEnterAction(player, id)
                    runLeaveAction(player, id)
                    runTickAction(player, id)
                }
            }
        }
    }
}
