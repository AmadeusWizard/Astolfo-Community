package xyz.astolfo.astolfocommunity.commands

import com.google.common.cache.CacheBuilder
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import java.util.concurrent.TimeUnit

class ChannelListener(
    val application: AstolfoCommunityApplication,
    val guildListener: GuildListener
) {

    private val memberCache = CacheBuilder.newBuilder()
        .removalListener<Member, SessionListener> { (_, listener) ->
            listener.dispose()
        }
        .weakKeys()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build<Member, SessionListener>()

    fun processMessage(timeIssued: Long, event: GuildMessageReceivedEvent) {
        val user: Member = event.member
        val sessionEntry = memberCache.getIfPresent(user) ?: return // Ignore if session is invalid
        sessionEntry.processMessage(timeIssued, event)
    }

    fun processCommand(timeIssued: Long, matchedPrefix: String, event: GuildMessageReceivedEvent) {
        // forward to and create session listener
        val member: Member = event.member

        val entry = memberCache.get(member) {
            // Create session listener if it doesn't exist
            //println("CREATE SESSIONLISTENER: ${guild.idLong}/${channel.idLong}/${member.user.idLong}")
            SessionListener(application, this)
        }
        entry.processCommand(timeIssued, matchedPrefix, event)
    }

    fun dispose() {
        memberCache.invalidateAll()
    }
}