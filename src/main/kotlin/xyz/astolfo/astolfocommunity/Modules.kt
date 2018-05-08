package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.JDAInfo
import java.awt.Color
import java.text.DecimalFormat


val modules = initModules()

fun initModules(): List<Module> {
    val infomodule = createInfoModule()
    val funModule = createFunModule()
    val musicModule = createMusicModule()

    return listOf(infomodule, funModule, musicModule)
}

class Module(val name: String, val commands: List<Command>)

class ModuleBuilder(val name: String) {
    var commands = mutableListOf<Command>()
    fun build() = Module(name, commands)
}

fun module(name: String, builder: ModuleBuilder.() -> Unit): Module {
    val moduleBuilder = ModuleBuilder(name)
    builder.invoke(moduleBuilder)
    return moduleBuilder.build()
}

fun ModuleBuilder.command(name: String, vararg alts: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(name, alts)
    builder.invoke(commandBuilder)
    commands.add(commandBuilder.build())
}