package com.panomc.plugins.market.runtime

import com.panomc.plugins.market.MarketPlugin
import org.springframework.beans.factory.NoSuchBeanDefinitionException

/**
 * The bean lookup of the market's route and job wiring. The plugin's own beans (DAOs, services, `@Endpoint`s) live in
 * `plugin.pluginBeanContext`; the platform's (`DatabaseManager`, `AuthProvider`, `ConfigManager`, ...) live in
 * `plugin.applicationContext`, which does not know the plugin's. A lookup tries the plugin context first and the platform context second, so
 * wiring code never has to know where a bean lives. (The first real-instance run, MK-080, found every `plugin.applicationContext.getBean(MarketXDao)`
 * of the wiring helpers failing with `NoSuchBeanDefinitionException`; tests build their object graph by hand and never saw it.)
 */
internal class PluginBeans(private val plugin: MarketPlugin) {
    fun <T : Any> getBean(type: Class<T>): T = try {
        plugin.pluginBeanContext.getBean(type)
    } catch (e: NoSuchBeanDefinitionException) {
        plugin.applicationContext.getBean(type)
    }
}

internal val MarketPlugin.beans: PluginBeans get() = PluginBeans(this)
