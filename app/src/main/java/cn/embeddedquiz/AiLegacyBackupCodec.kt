package cn.embeddedquiz

import org.json.JSONArray
import org.json.JSONObject

/** Legacy sections retain the v1..3 field/default/preservation rules; never read credentials. */
internal object AiLegacyBackupCodec {
    val tables=linkedMapOf("questions" to "questions","progress" to "progress","attempts" to "attempts","ai" to "ai",
        "stickyNotes" to "sticky_notes","learningSettings" to "learning_settings","personalAnswers" to "personal_answers","questionPersonal" to "question_personal")
    val booleans=mapOf("progress" to setOf("wrong","favorite","flagged"),"attempts" to setOf("correct","wasNew"),
        "learningSettings" to setOf("reminderEnabled","notificationPermissionAsked"),"questionPersonal" to setOf("codeVerified"))
    fun wire(section:String,row:JSONObject):JSONObject {
        if(section=="questions") return JSONObject(row.getString("json")).put("_storedJson",row.getString("json"))
        val o=JSONObject(row.toString())
        if(section=="attempts") o.remove("id")
        if(section=="learningSettings") o.remove("id")
        for(k in booleans[section].orEmpty()) if(!o.isNull(k)) {
            val n=o.getLong(k);require(n in 0..1);o.put(k,n==1L)
        }
        return o
    }
    fun key(section:String,o:JSONObject,ordinal:Long):String = when(section) {
        "questions","stickyNotes" -> o.getString("id")
        "progress","personalAnswers","questionPersonal" -> o.getString("questionId")
        "ai" -> o.getString("cacheKey")
        "learningSettings" -> "1"
        "attempts" -> ordinal.toString()
        else -> error("UNKNOWN_SECTION")
    }
    fun canonical(v:Any?):String = when(v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",","{","}") { AiJson.canonical(it)+":"+canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",","[","]") { canonical(v.get(it)) }
        is Double -> { require(v.isFinite());JSONObject.numberToString(v) }
        else -> AiJson.canonical(v)
    }
    /** Output is a fully validated SQL row, not a whole-file JSONObject. */
    fun validate(section:String,o:JSONObject,version:Int=4,question:(String)->JSONObject?):JSONObject {
        fun q(id:String):JSONObject=requireNotNull(question(id)) { "MISSING_QUESTION" }
        // JSONObject.getInt can wrap a wider Number. Reject out-of-protocol values before conversion.
        val intFields=when(section) {
            "progress" -> listOf("seen","correctCount","repetitions","interval","lapses")
            "ai" -> listOf("inputTokens","outputTokens","hitTokens")
            "learningSettings" -> listOf("dailyGoal","reminderMinute")
            else -> emptyList()
        }
        intFields.forEach { Math.toIntExact(AiJson.long(o,it)) }
        when(section) {
            "questions" -> {
                val plain=JSONObject(o.toString());val saved=if(version>=4 && plain.opt("_storedJson") is String) plain.remove("_storedJson") as String else null
                if(saved!=null) {
                    val original=JSONObject(saved);original.remove("_storedJson")
                    require(canonical(original)==canonical(plain)) { "QUESTION_STORAGE_MISMATCH" }
                }
                val row=BankCodec.parseBank(JSONObject().put("schemaVersion",1).put("questions",JSONArray().put(plain)).toString()).single()
                val raw=saved ?: row.json
                HistoricalQuestionV3.read(row.id,raw)
                return JSONObject().put("id",row.id).put("json",raw).put("kind",row.kind).put("source",row.source).put("category",row.category)
                    .put("normalizedCategory",row.normalizedCategory).put("company",row.company).put("difficulty",row.difficulty)
                    .put("reviewRequired",row.reviewRequired).put("stem",row.stem).put("correctKeys",row.correctKeys).put("searchText",row.searchText)
                    .put("hasAnswer",row.hasAnswer).put("contentHash",row.contentHash).put("optionKeys",row.optionKeys)
            }
            "progress" -> {
                q(o.getString("questionId"))
                val p=Progress(o.getString("questionId"),o.getInt("seen"),o.getInt("correctCount"),o.getInt("repetitions"),o.getInt("interval"),o.getDouble("ease"),
                    o.getLong("due"),o.getInt("lapses"),o.getBoolean("wrong"),o.getBoolean("favorite"),o.getBoolean("flagged"),o.getString("code"),o.getString("notes"))
                require(p.seen>=0 && p.correctCount in 0..p.seen && p.interval in 0..3650 && p.ease in 1.3..3.5 && p.repetitions>=0 && p.lapses>=0 && p.due in 0..3650000)
                return JSONObject().put("questionId",p.questionId).put("seen",p.seen).put("correctCount",p.correctCount).put("repetitions",p.repetitions)
                    .put("interval",p.interval).put("ease",p.ease).put("due",p.due).put("lapses",p.lapses).put("wrong",p.wrong).put("favorite",p.favorite)
                    .put("flagged",p.flagged).put("code",p.code).put("notes",p.notes)
            }
            "attempts" -> {
                val row=q(o.getString("questionId"));Feedback.valueOf(o.getString("feedback"))
                val assessment=o.optString("assessment","");require(assessment.isEmpty() || Assessment.entries.any { it.name==assessment })
                val source=o.optString("answerSource",if(assessment.isNotEmpty() || !o.isNull("correct") && row.getString("kind") !in setOf("SINGLE","MULTIPLE","TRUE_FALSE")) "MANUAL" else if(o.isNull("correct")) "UNSCORED" else "ORIGINAL")
                return JSONObject().put("questionId",o.getString("questionId")).put("timestamp",o.getLong("timestamp")).put("day",o.getLong("day"))
                    .put("response",o.getString("response")).put("correct",if(o.isNull("correct")) JSONObject.NULL else o.getBoolean("correct"))
                    .put("feedback",o.getString("feedback")).put("mode",o.getString("mode")).put("wasNew",o.getBoolean("wasNew"))
                    .put("assessment",assessment).put("answerSource",source).put("normalizedCategory",o.optString("normalizedCategory",row.getString("normalizedCategory")))
                    .put("questionHash",o.optString("questionHash"))
            }
            "ai" -> {
                q(o.getString("questionId"))
                val input=o.getInt("inputTokens");val output=o.getInt("outputTokens");val hit=o.getInt("hitTokens");val estimate=o.getDouble("estimatedUsd")
                require(input>=0 && output>=0 && hit in 0..input && estimate.isFinite() && estimate>=0)
                return JSONObject().put("cacheKey",o.getString("cacheKey")).put("questionId",o.getString("questionId")).put("model",o.getString("model"))
                    .put("prompt",o.getString("prompt")).put("response",o.getString("response")).put("inputTokens",input).put("outputTokens",output).put("hitTokens",hit)
                    .put("estimatedUsd",estimate).put("created",o.getLong("created")).put("contentHash",o.optString("contentHash"))
                    .put("requestType",o.optString("requestType","FOLLOW_UP")).put("day",o.optLong("day",java.time.Instant.ofEpochMilli(o.getLong("created")).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()))
            }
            "stickyNotes" -> {
                val id=o.getString("id");val created=o.getLong("created");val updated=o.getLong("updated")
                require(id.isNotBlank() && id.length<=200 && created>=0 && updated>=created)
                val note = JSONObject().put("id",id).put("title",o.getString("title"))
                    .put("content",o.getString("content")).put("created",created).put("updated",updated)
                if (version >= 5) {
                    val source = listOf("sourceRequestId", "sourceCommandId", "sourceQuestionId", "sourceQuestionHash", "sourceQuestionLabel")
                    val values = source.map { if (o.isNull(it)) null else AiJson.string(o, it) }
                    require(values.all { it == null } || values.all { it != null }) { "INCOMPLETE_NOTE_SOURCE" }
                    if (values.first() != null) {
                        val (request, command, questionId, hash, label) = values.filterNotNull()
                        AiJson.uuid(request)
                        AiJson.uuid(command)
                        AiJson.hash(hash)
                        require(questionId.isNotBlank() && questionId.length <= 200 && label.isNotBlank() && label.length <= 120)
                        source.zip(values).forEach { (key, value) -> note.put(key, value) }
                    }
                }
                return note
            }
            "learningSettings" -> return LearningCodec.settings(LearningCodec.parseSettings(o)).put("id",1)
            "personalAnswers" -> return LearningCodec.answer(LearningCodec.parseAnswer(o))
            "questionPersonal" -> {
                val value=LearningCodec.parseMetadata(o)
                return JSONObject().put("questionId",value.questionId).put("tags",value.tags).put("perceivedDifficulty",value.perceivedDifficulty)
                    .put("verifiedCode",value.verifiedCode).put("codeVerified",value.codeVerified)
            }
        }
        error("UNKNOWN_SECTION")
    }
}
