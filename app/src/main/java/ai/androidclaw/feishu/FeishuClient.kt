package ai.androidclaw.feishu

import com.lark.oapi.Client
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import java.util.UUID

class FeishuClient(config: FeishuConfig) {

    private val client = Client
        .newBuilder(config.appId, config.appSecret)
        .build()

    suspend fun sendMessage(
        receiveIdType: String,
        receiveId: String,
        msgType: String,
        content: String
    ): Result<String> {
        return runCatching {
            val req = CreateMessageReq
                .newBuilder()
                .receiveIdType(receiveIdType)
                .createMessageReqBody(
                    CreateMessageReqBody
                        .newBuilder()
                        .receiveId(receiveId)
                        .msgType(msgType)
                        .content(content)
                        .uuid(UUID.randomUUID().toString())
                        .build()
                )
                .build()

            val resp = client.im().v1().message().create(req)
            if (!resp.success()) {
                throw IllegalStateException("Failed to send message: ${resp.msg}")
            }

            resp.data?.messageId ?: ""
        }
    }

    suspend fun replyMessage(messageId: String, msgType: String, content: String): Result<String> {
        return runCatching {
            val req = ReplyMessageReq
                .newBuilder()
                .messageId(messageId)
                .replyMessageReqBody(
                    ReplyMessageReqBody
                        .newBuilder()
                        .msgType(msgType)
                        .content(content)
                        .uuid(UUID.randomUUID().toString())
                        .build()
                )
                .build()

            val resp = client.im().v1().message().reply(req)
            if (!resp.success()) {
                throw IllegalStateException("Failed to reply message: ${resp.msg}")
            }

            resp.data?.messageId ?: messageId
        }
    }
}
