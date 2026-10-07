package com.fromwau.kortex.polkit

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.mapError
import com.fromwau.kortex.auth.AuthConversation
import kotlinx.coroutines.flow.StateFlow

/**
 * One authentication polkitd has asked this agent for: what is about to be done, and the password that
 * allows it.
 *
 * It leaves [PolkitAgent.serve]'s list once [conversation] has ended, and polkitd is told the outcome.
 *
 * @property action the action's id, such as `org.freedesktop.systemd1.manage-units`.
 * @property message what the action does, in the words of the policy that defines it.
 * @property icon a freedesktop icon name for the action, or null where it names none.
 * @property details facts about this request in particular, keyed by names the action chose.
 * @property users every user polkitd will take the password of, this process's own first.
 */
public class PolkitRequest internal constructor(
    public val action: String,
    public val message: String,
    public val icon: String?,
    public val details: Map<String, String>,
    public val users: List<String>,
    private val helper: HelperConversation,
    internal val cookie: String,
) {
    /** Asks for the password of [user], and goes on asking through a switch to another. */
    public val conversation: AuthConversation get() = helper

    /** The user whose password [conversation] asks for now, one of [users]. */
    public val user: StateFlow<String> get() = helper.user

    /**
     * Asks for [user]'s password instead, starting the conversation again with no notes.
     *
     * @return [UserSwitchError.NotOffered] where [user] is not one of [users], and
     *   [UserSwitchError.AlreadyEnded] once the conversation is over.
     */
    public suspend fun switchUser(user: String): EmptyResult<UserSwitchError> {
        if (user !in users) return Err(UserSwitchError.NotOffered(user))
        return helper.restartAs(user).mapError { ended -> UserSwitchError.AlreadyEnded(ended.outcome) }
    }
}
