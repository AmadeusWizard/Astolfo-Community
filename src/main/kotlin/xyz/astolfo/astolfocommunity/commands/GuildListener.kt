package xyz.astolfo.astolfocommunity.commands

import com.google.common.cache.CacheBuilder
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import java.util.concurrent.TimeUnit

class GuildListener(
    val application: AstolfoCommunityApplication,
    val messageListener: MessageListener
) {

    private val channelCache = CacheBuilder.newBuilder()
        .removalListener<TextChannel, ChannelListener> { (_, listener) ->
            listener.dispose()
        }
        .weakKeys()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build<TextChannel, ChannelListener>()

    fun processMessageEvent(timeIssued: Long, event: GuildMessageReceivedEvent) {
        val botId = event.jda.selfUser.idLong
        val prefix = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
            .getEffectiveGuildPrefix(application)
        val channel: TextChannel = event.channel

        val rawMessage: String = event.message.contentRaw
        val validPrefixes = listOf(prefix, "<@$botId>", "<@!$botId>")

        val matchedPrefix = validPrefixes.find { rawMessage.startsWith(it, true) }

        // This only is true when a user says a normal message
        if (matchedPrefix == null) {
            val channelEntry = channelCache.getIfPresent(channel)
                ?: return // Ignore if channel listener is invalid
            channelEntry.processMessage(timeIssued, event)
            return
        }
        // Process the message as if it was a command
        val listener = channelCache.get(channel) {
            // Create channel listener if it doesn't exist
            //println("CREATE CHANNELLISTENER: ${guild.idLong}/${channel.idLong}")
            ChannelListener(application, this)
        }
        listener.processCommand(timeIssued, matchedPrefix, event)
    }

    fun dispose() {
        channelCache.invalidateAll()
    }

}