package com.openminis.app.agent.prompts

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.SkillRepository

/** Explicitly editing the bundled skill template also edits its installed file.
 * No startup overwrite: locally modified/imported skills otherwise stay user-owned.
 */
internal object BuiltinSkillPrompt {
    const val ID = "data.repository.skillrepository.4deb7617f9a6"

    fun apply(context: Context, id: String, text: String) {
        if (id != ID) return
        // Metadata must remain parseable; otherwise a successful save would
        // silently unregister the installed skill on the next disk reload.
        require(SkillRepository.parseSkillMd(text) != null) {
            "The skill template needs valid name and description frontmatter."
        }
        val app = context.applicationContext as? MinisApp
            ?: error("Application is not ready to update the installed skill")
        check(app.skillRepository.writeSkillFile("skill-creator", "SKILL.md", text)) {
            "Could not update the installed skill-creator/SKILL.md"
        }
    }
}
