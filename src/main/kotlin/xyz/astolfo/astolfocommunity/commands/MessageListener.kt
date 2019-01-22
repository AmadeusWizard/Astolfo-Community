package xyz.astolfo.astolfocommunity.commands

import com.google.common.cache.CacheBuilder
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.hooks.EventListener
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.RateLimiter
import java.util.concurrent.TimeUnit

class MessageListener(val application: AstolfoCommunityApplication) : EventListener {

    private val guildCache = CacheBuilder.newBuilder()
        .removalListener<Guild, GuildListener> { (_, listener) ->
            listener.dispose()
        }
        .weakKeys()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build<Guild, GuildListener>()

    val commandRateLimiter = RateLimiter<Long>(4, 6)
    val chatBotManager = ChatBotManager(application.properties)

    override fun onEvent(event: Event) {
        if (event !is GuildMessageReceivedEvent) return
        val timeIssued = System.nanoTime()
        application.statsDClient.incrementCounter("messages_received")
        if (event.author.isBot || event.isWebhookMessage || !event.channel.canTalk()) return
        val listener = guildCache.get(event.guild) {
            GuildListener(application, this)
        }
        listener.processMessageEvent(timeIssued, event)
    }

}