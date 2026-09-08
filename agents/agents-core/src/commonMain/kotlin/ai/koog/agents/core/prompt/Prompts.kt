package ai.koog.agents.core.prompt

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.text.TextContentBuilderBase

internal object Prompts {
    fun TextContentBuilderBase<*>.selectRelevantTools(tools: List<ToolDescriptor>, subtaskDescription: String) =
        markdown {
            +"You will be now concentrating on solving the following task:"
            br()

            h2("TASK DESCRIPTION")
            br()
            +subtaskDescription
            br()

            h2("AVAILABLE TOOLS")
            br()
            +"You have the following tools available:"
            br()
            bulleted {
                tools.forEach {
                    item("Name: ${it.name}\nDescription: ${it.description}")
                }
            }
            br()
            br()

            +"Please, provide a list of the tools ONLY RELEVANT FOR THE GIVEN TASK, separated by commas."
            +"Think carefully about the tools you select, and make sure they are relevant to the task."
        }

    fun TextContentBuilderBase<*>.summariseForContinuation(maxTokens: Int?) =
        markdown {
            +"Write a concise handover for continuing the conversation from the covered messages above."
            +(
                "Recent turns will follow the handover. Preserve the objective, constraints, decisions, exact facts and " +
                    "identifiers, and unfinished work needed to continue."
                )
            +(
                "Keep source attribution: distinguish user requests, assistant proposals and tool observations. " +
                    "Separate completed actions from proposed or pending actions."
                )
            +(
                "If a previous handover body is present, update it using the newly covered messages: retain valid prior " +
                    "state, replace superseded decisions and facts, and remove resolved work. Include each fact once."
                )
            +(
                "Use short prose or bullets as useful; omit empty or repeated sections, runtime commentary, and these " +
                    "summarisation directions. These directions are not user objectives."
                )
            +"Return only the handover body; the runtime supplies its receiving frame."
            if (maxTokens != null) {
                +"The output limit is $maxTokens tokens, a ceiling rather than a target. Use fewer tokens when sufficient."
            }
        }

    fun TextContentBuilderBase<*>.summarizeInTLDR() =
        markdown {
            +"Create a comprehensive summary of this conversation."
            br()
            +"Include the following in your summary:"
            numbered {
                item("Key objectives and problems being addressed")
                item("All tools used along with their purpose and outcomes")
                item("Critical information discovered or generated")
                item("Current progress status and conclusions reached")
                item("Any pending questions or unresolved issues")
            }
            br()
            +"FORMAT YOUR SUMMARY WITH CLEAR SECTIONS for easy reference, including:"
            bulleted {
                item("Key Objectives")
                item("Tools Used & Results")
                item("Key Findings")
                item("Current Status")
                item("Next Steps")
            }
            br()
            +"This summary will be the ONLY context available for continuing this conversation, along with the system message."
            +"Ensure it contains ALL essential information needed to proceed effectively."
        }
}
