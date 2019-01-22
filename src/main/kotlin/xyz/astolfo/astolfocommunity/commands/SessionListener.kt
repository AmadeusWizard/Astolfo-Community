package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.*
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.AstolfoPermissionUtils
import xyz.astolfo.astolfocommunity.hasPermission
import xyz.astolfo.astolfocommunity.messages.errorEmbed
import xyz.astolfo.astolfocommunity.modules.Module
import xyz.astolfo.astolfocommunity.modules.modules
import xyz.astolfo.astolfocommunity.splitFirst
import java.util.concurrent.TimeUnit

class SessionListener(
    val application: AstolfoCommunityApplication,
    val channelListener: ChannelListener
) {

    private var currentSession: CommandSession? = null
    private var sessionJob: Job? = null

    fun processCommand(timeIssued: Long, matchedPrefix: String, event: GuildMessageReceivedEvent) {
        val member = event.member
        val channel = event.channel

        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
        val channelBlacklisted = guildSettings.blacklistedChannels.contains(channel.idLong)

        val rawContent = event.message.contentRaw!!
        val isMention = matchedPrefix.startsWith("<@")

        var commandMessage = rawContent.substring(matchedPrefix.length).trim()

        var commandNodes = resolvePath(commandMessage)

        var checkedRateLimit = false

        if (commandNodes == null) {
            if (channelBlacklisted) return // Ignore chat bot if channel is blacklisted
            if (!isMention) return
            if (!checkPatreonBot(event)) return
            if (!processRateLimit(event)) return
            checkedRateLimit = true
            // Not a command but rather a chat bot message
            if (commandMessage.isEmpty()) {
                channel.sendMessage("Hi :D").queue()
                return
            }
            if (commandMessage.contains("prefix", true)) {
                channel.sendMessage(
                    "Yahoo! My prefix in this guild is **${guildSettings.getEffectiveGuildPrefix(
                        application
                    )}**!"
                ).queue()
                return
            }
            val chatBotManager = channelListener.guildListener.messageListener.chatBotManager

            val response = chatBotManager.process(member, commandMessage)
            if (response.type == ChatResponse.ResponseType.COMMAND) {
                commandMessage = response.response
                commandNodes = resolvePath(commandMessage)
                if (commandNodes == null) return // cancel the command
            } else {
                channel.sendMessage(response.response).queue()
                return
            }
        } else {
            if (!checkPatreonBot(event)) return
        }
        // Only allow Admin module if blacklisted
        if (channelBlacklisted) {
            val module = commandNodes.first
            if (!module.name.equals("Admin", true)) return
        }

        if (!checkedRateLimit && !processRateLimit(event)) return

        // Process Command
        application.statsDClient.incrementCounter("commands_executed")

        if (!channel.hasPermission(Permission.MESSAGE_EMBED_LINKS)) {
            channel.sendMessage("Please enable **embed links** to use Astolfo commands.").queue()
            return
        }

        fun createExecution(session: CommandSession, commandPath: String, commandContent: String) =
            CommandExecution(
                application,
                event,
                session,
                commandPath,
                commandContent,
                timeIssued
            )

        val module = commandNodes.first

        val moduleExecution = createExecution(InheritedCommandSession(commandMessage), "", commandMessage)
        if (!module.inheritedActions.all {
                runBlocking { it.invoke(moduleExecution) }
            }) return

        // Go through all the nodes in the command path and check permissions/actions
        for ((command, commandPath, commandContent) in commandNodes.second) {
            // PERMISSIONS
            val permission = command.permission

            var hasPermission: Boolean? = if (member.hasPermission(Permission.ADMINISTRATOR)) true else null
            // Check discord permission if the member isn't a admin already
            if (hasPermission != true && permission.permissionDefaults.isNotEmpty())
                hasPermission = member.hasPermission(channel, *permission.permissionDefaults)
            // Check Astolfo permission if discord permission didn't already grant permissions
            if (hasPermission != true)
                AstolfoPermissionUtils.hasPermission(
                    member,
                    channel,
                    application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong).permissions,
                    permission
                )?.let { hasPermission = it }

            if (hasPermission == false) {
                channel.sendMessage(
                    errorEmbed(
                        "You are missing the astolfo **${permission.path}**${if (permission.permissionDefaults.isNotEmpty())
                            " or discord ${permission.permissionDefaults.joinToString(", ") { "**${it.getName()}**" }}" else ""} permission(s)"
                    )
                )
                    .queue()
                return
            }

            // INHERITED ACTIONS
            val inheritedExecution =
                createExecution(InheritedCommandSession(commandPath), commandPath, commandContent)
            if (!command.inheritedActions.all {
                    runBlocking { it.invoke(inheritedExecution) }
                }) return
        }
        // COMMAND ENDPOINT
        val (command, commandPath, commandContent) = commandNodes.second.last()

        fun runNewSession() {
            cleanUp()
            application.statsDClient.incrementCounter("commandExecuteCount", "command:$commandPath")
            currentSession = CommandSessionImpl(commandPath)
            val execution = createExecution(currentSession!!, commandPath, commandContent)
            sessionJob = GlobalScope.launch {
                withTimeout(TimeUnit.MINUTES.toMillis(1)) {
                    command.action(execution)
                }
            }
        }

        val currentSession = this.currentSession

        // Checks if command is the same as the previous, if so, check if its a follow up response
        if (currentSession != null && currentSession.commandPath.equals(commandPath, true)) {
            val action =
                currentSession.onMessageReceived(createExecution(currentSession, commandPath, commandContent))
            when (action) {
                CommandSession.ResponseAction.RUN_COMMAND -> {
                    runNewSession()
                }
                CommandSession.ResponseAction.IGNORE_COMMAND -> {
                }
                else -> TODO("Invalid action: $action")
            }
        } else {
            runNewSession()
        }
    }

    fun processMessage(timeIssued: Long, event: GuildMessageReceivedEvent) {
        val currentSession = this.currentSession ?: return
        // TODO add rate limit
        //if (!processRateLimit(event)) return@launch
        val execution = CommandExecution(
            application,
            event,
            currentSession,
            currentSession.commandPath,
            event.message.contentRaw,
            timeIssued
        )
        if (currentSession.onMessageReceived(execution) == CommandSession.ResponseAction.RUN_COMMAND) {
            // If the response listeners return true or all the response listeners removed themselves
            cleanUp()
        }
    }

    private fun checkPatreonBot(event: GuildMessageReceivedEvent): Boolean {
        if (!application.properties.patreon_bot) return true
        val staffIds = application.staffMemberIds
        if (staffIds.contains(event.author.idLong)) return true
        val donorGuild = application.donationManager.getByMember(event.guild.owner)
        if (!donorGuild.patreonBot) {
            event.channel.sendMessage(
                errorEmbed("In order to use the high quality patreon bot, the owner of your guild must pledge at least $10 on [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)")
            ).queue()
            return false
        }
        return true
    }

    private fun processRateLimit(event: GuildMessageReceivedEvent): Boolean =
        runBlocking {
            val rateLimiter = channelListener.guildListener.messageListener.commandRateLimiter
            val user = event.author.idLong
            val wasLimited = rateLimiter.isLimited(user)
            rateLimiter.add(user)
            if (wasLimited) return@runBlocking false
            if (rateLimiter.isLimited(user)) {
                event.channel.sendMessage("${event.member.asMention} You have been ratelimited! Please wait a little and try again!")
                    .queue()
                return@runBlocking false
            }
            return@runBlocking true
        }

    private fun resolvePath(commandMessage: String): Pair<Module, List<PathNode>>? {
        for (module in modules) return module to (resolvePath(module.commands, "", commandMessage) ?: continue)
        return null
    }

    private fun resolvePath(commands: List<Command>, commandPath: String, commandMessage: String): List<PathNode>? {
        val (commandName, commandContent) = commandMessage.splitFirst(" ")

        val command = commands.findByName(commandName) ?: return null

        val newCommandPath = "$commandPath ${command.name}".trim()
        val commandNode = PathNode(command, newCommandPath, commandContent)

        if (commandContent.isBlank()) return listOf(commandNode)

        val subPath = resolvePath(command.subCommands, newCommandPath, commandContent) ?: listOf(commandNode)
        return listOf(commandNode, *subPath.toTypedArray())
    }

    data class PathNode(val command: Command, val commandPath: String, val commandContent: String)


    private fun cleanUp() {
        currentSession?.destroy()
        sessionJob?.cancel()
        currentSession = null
        sessionJob = null
    }

    fun dispose() {

    }

}