package com.readygym.core.ai

import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.WorkoutSession

/** Sends a chat to a JSON-mode model and returns the raw content. Blocking; call it off the main thread. */
fun interface ChatModel {
    fun complete(messages: List<ChatMessage>): String
}

/** The model kept breaking the rules after a correction round. */
class PlanRejectedException(val problems: List<String>) :
    Exception("AI 给出的计划不符合要求：" + problems.take(3).joinToString("；"))

/** A plan the model proposed; nothing is stored until the user confirms it. */
data class PlanProposal(val plan: WorkoutPlan, val message: String, val reasons: Map<String, String>)

class PlanCoach(private val model: ChatModel, private val library: ExerciseLibrary) {

    fun generate(profile: UserProfile): PlanProposal {
        val contract = PlanContract(library, profile)
        val reply = ask(contract, contract.generateMessages())
        return PlanProposal(
            WorkoutPlan(0, 0, "初始计划", reply.summary, profile.daysPerWeek, reply.days).withDefaultRest(),
            reply.message, reply.reasons,
        )
    }

    fun adjust(
        profile: UserProfile,
        current: WorkoutPlan,
        session: WorkoutSession,
        feedback: PostWorkoutFeedback,
        recent: List<WorkoutSession> = emptyList(),
    ): PlanProposal {
        val contract = PlanContract(library, profile)
        val reply = ask(contract, contract.adjustMessages(current, session, feedback, recent))
        return PlanProposal(
            current.copy(reason = "练后调整：${session.dayTitle}", summary = reply.summary.ifBlank { current.summary }, days = reply.days).withDefaultRest(),
            reply.message, reply.reasons,
        )
    }

    private fun ask(contract: PlanContract, messages: List<ChatMessage>): PlanReply.Valid {
        val content = model.complete(messages)
        when (val first = contract.parse(content)) {
            is PlanReply.Valid -> return first
            is PlanReply.Invalid -> {
                val retry = messages + ChatMessage("assistant", content) + contract.retryMessage(first.problems)
                return when (val second = contract.parse(model.complete(retry))) {
                    is PlanReply.Valid -> second
                    is PlanReply.Invalid -> throw PlanRejectedException(second.problems)
                }
            }
        }
    }
}
