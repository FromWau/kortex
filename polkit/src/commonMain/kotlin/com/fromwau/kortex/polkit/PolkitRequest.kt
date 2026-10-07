package com.fromwau.kortex.polkit

import com.fromwau.kortex.auth.AuthConversation

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
 * @property user the user whose password [conversation] asks for.
 */
public class PolkitRequest internal constructor(
    public val action: String,
    public val message: String,
    public val icon: String?,
    public val details: Map<String, String>,
    public val user: String,
    public val conversation: AuthConversation,
    internal val cookie: String,
)
