package com.example.bot

import com.example.bot.plugins.configureSecurity
import com.example.bot.routes.CampaignDto
import com.example.bot.routes.CampaignService
import com.example.bot.routes.CampaignStatus
import com.example.bot.routes.TxNotifyService
import com.example.bot.routes.notifyRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

class NotifyRoutesTest : StringSpec({
    fun io.ktor.server.application.Application.testModule() {
        install(ContentNegotiation) { json() }
        configureSecurity()
        notifyRoutes(TxNotifyService(), CampaignService())
    }

    "enqueue tx" {
        testApplication {
            application { testModule() }
            val client = createClient { }
            val resp = client.post("/api/notify/tx") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"chatId":1,"messageThreadId":null,"method":"TEXT","text":"hi","parseMode":null,"photoUrl":null,"album":null,"buttons":null,"dedupKey":null}""",
                )
            }
            resp.status shouldBe HttpStatusCode.Accepted
        }
    }

    "campaign lifecycle" {
        testApplication {
            application { testModule() }
            val client = createClient { }

            val create = client.post("/api/campaigns") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"t","text":"hello"}""")
            }
            create.status shouldBe HttpStatusCode.OK
            val dto = Json.decodeFromString<CampaignDto>(create.bodyAsText())
            dto.status shouldBe CampaignStatus.DRAFT

            val id = dto.id

            val update = client.put("/api/campaigns/$id") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"t2"}""")
            }
            update.status shouldBe HttpStatusCode.OK

            client.post("/api/campaigns/$id:preview?user_id=1")

            client.post("/api/campaigns/$id:schedule") {
                contentType(ContentType.Application.Json)
                setBody("""{"cron":"* * * * *"}""")
            }

            client.post("/api/campaigns/$id:send-now")

            client.post("/api/campaigns/$id:pause")

            client.post("/api/campaigns/$id:resume")

            val get = client.get("/api/campaigns/$id")
            get.status shouldBe HttpStatusCode.OK

            val list = client.get("/api/campaigns")
            list.status shouldBe HttpStatusCode.OK
        }
    }
})
