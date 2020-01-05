package org.fs.chm.dao

import java.io.File
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

import cats.effect._
import cats.implicits._
import com.github.nscala_time.time.Imports._
import doobie._
import doobie.free.connection
import doobie.h2.implicits._
import doobie.implicits._
import org.fs.chm.utility.IoUtils
import org.fs.utility.StopWatch
import org.slf4s.Logging

class H2ChatHistoryDao(
    override val dataPathRoot: File,
    txctr: Transactor.Aux[IO, Connection]
) extends ChatHistoryDao
    with Logging {

  import org.fs.chm.dao.H2ChatHistoryDao._

  override def datasets: Seq[Dataset] = {
    queries.datasets.selectAll.transact(txctr).unsafeRunSync()
  }

  override def myself(dsUuid: UUID): User = {
    queries.users.selectMyself(dsUuid).transact(txctr).unsafeRunSync()
  }

  /** Contains myself as well */
  override def users(dsUuid: UUID): Seq[User] = {
    queries.users.selectAll(dsUuid).transact(txctr).unsafeRunSync()
  }

  override def chats(dsUuid: UUID): Seq[Chat] = {
    queries.chats.selectAll(dsUuid).transact(txctr).unsafeRunSync()
  }

  private lazy val interlocutorsCache: Map[UUID, Map[Chat, Seq[User]]] = {
    (for {
      ds <- datasets.par
      myself1 = myself(ds.uuid)
      users1 = users(ds.uuid)
    } yield {
      val chatInterlocutors = chats(ds.uuid).map { c =>
        val ids: Seq[Long] = queries.users.selectInterlocutorIds(c.id).transact(txctr).unsafeRunSync()
        val usersWithoutMe = users1.filter(u => u != myself1 && ids.contains(u.id))
        (c, myself1 +: usersWithoutMe.sortBy(u => (u.id, u.prettyName)))
      }.toMap
      (ds.uuid, chatInterlocutors)
    }).seq.toMap
  }

  override def interlocutors(chat: Chat): Seq[User] =
    (for {
      c1 <- interlocutorsCache.get(chat.dsUuid)
      c2 <- c1.get(chat)
    } yield c2) getOrElse Seq.empty

  override def lastMessages(chat: Chat, limit: Int): IndexedSeq[Message] = {
    val raws = queries.rawMessages.selectLastInversed(chat, limit).transact(txctr).unsafeRunSync().reverse
    raws map Raws.toMessage
  }

  override def messagesBefore(chat: Chat, msgId: Long, limit: Int): Option[IndexedSeq[Message]] = {
    messageOption(chat, msgId) map { m =>
      val raws = queries.rawMessages.selectBeforeInversed(chat, m, limit).transact(txctr).unsafeRunSync().reverse
      raws map Raws.toMessage
    }
  }

  override def messageOption(chat: Chat, id: Long): Option[Message] = {
    queries.rawMessages.selectOption(chat, id).transact(txctr).unsafeRunSync().map(Raws.toMessage)
  }

  def createTables(): Unit = {
    val createQueries = Seq(
      sql"""
        CREATE TABLE datasets (
          uuid                UUID NOT NULL PRIMARY KEY,
          alias               VARCHAR(255),
          source_type         VARCHAR(255)
        );
      """,
      sql"""
        CREATE TABLE users (
          ds_uuid             UUID NOT NULL,
          id                  BIGINT NOT NULL,
          first_name          VARCHAR(255),
          last_name           VARCHAR(255),
          username            VARCHAR(255),
          phone_number        VARCHAR(20),
          last_seen_time      TIMESTAMP,
          -- Not in the entity
          is_myself           BOOLEAN NOT NULL,
          PRIMARY KEY (ds_uuid, id),
          FOREIGN KEY (ds_uuid) REFERENCES datasets (uuid)
        );
      """,
      sql"""
        CREATE TABLE chats (
          ds_uuid             UUID NOT NULL,
          id                  BIGINT NOT NULL,
          name                VARCHAR(255),
          type                VARCHAR(255) NOT NULL,
          img_path            VARCHAR(4095),
          PRIMARY KEY (ds_uuid, id),
          FOREIGN KEY (ds_uuid) REFERENCES datasets (uuid)
        );
      """,
      sql"""
        CREATE TABLE messages (
          ds_uuid             UUID NOT NULL,
          chat_id             BIGINT NOT NULL,
          id                  BIGINT NOT NULL,
          message_type        VARCHAR(255) NOT NULL,
          time                TIMESTAMP NOT NULL,
          edit_time           TIMESTAMP,
          from_name           VARCHAR(255),
          from_id             BIGINT NOT NULL,
          forward_from_name   VARCHAR(255),
          reply_to_message_id BIGINT, -- not a reference
          title               VARCHAR(255),
          members             VARCHAR, -- serialized
          duration_sec        INT,
          discard_reason      VARCHAR(255),
          pinned_message_id   BIGINT,
          path                VARCHAR(4095),
          width               INT,
          height              INT,
          PRIMARY KEY (ds_uuid, chat_id, id),
          FOREIGN KEY (ds_uuid) REFERENCES datasets (uuid),
          FOREIGN KEY (chat_id) REFERENCES chats (id),
          FOREIGN KEY (from_id) REFERENCES users (id)
        );
      """,
      sql"""
        CREATE TABLE messages_text (
          id                  IDENTITY NOT NULL,
          ds_uuid             UUID NOT NULL,
          chat_id             BIGINT NOT NULL,
          message_id          BIGINT NOT NULL,
          plaintext           VARCHAR NOT NULL,
          PRIMARY KEY (id),
          UNIQUE      (ds_uuid, chat_id, message_id),
          FOREIGN KEY (ds_uuid) REFERENCES datasets (uuid),
          FOREIGN KEY (chat_id) REFERENCES chats (id),
          FOREIGN KEY (message_id) REFERENCES messages (id)
        );
      """,
      sql"""
        CREATE TABLE messages_text_element (
          id                  IDENTITY NOT NULL,
          text_id             BIGINT NOT NULL,
          element_type        VARCHAR(255) NOT NULL,
          text                VARCHAR,
          href                VARCHAR(4095),
          hidden              BOOLEAN,
          language            VARCHAR(4095),
          PRIMARY KEY (id),
          FOREIGN KEY (text_id) REFERENCES messages_text (id)
        );
      """,
      sql"""
        CREATE TABLE messages_content (
          id                  IDENTITY NOT NULL,
          ds_uuid             UUID NOT NULL,
          chat_id             BIGINT NOT NULL,
          message_id          BIGINT NOT NULL,
          element_type        VARCHAR(255) NOT NULL,
          path                VARCHAR(4095),
          thumbnail_path      VARCHAR(4095),
          emoji               VARCHAR(255),
          width               INT,
          height              INT,
          mime_type           VARCHAR(255),
          title               VARCHAR(255),
          performer           VARCHAR(255),
          lat                 DECIMAL(9,6),
          lon                 DECIMAL(9,6),
          duration_sec        INT,
          poll_question       VARCHAR,
          first_name          VARCHAR(255),
          last_name           VARCHAR(255),
          phone_number        VARCHAR(20),
          vcard_path          VARCHAR(4095),
          PRIMARY KEY (id),
          UNIQUE      (ds_uuid, chat_id, message_id),
          FOREIGN KEY (ds_uuid) REFERENCES datasets (uuid),
          FOREIGN KEY (chat_id) REFERENCES chats (id),
          FOREIGN KEY (message_id) REFERENCES messages (id)
        );
      """
    ) map (_.update.run)
    createQueries.reduce((a, b) => a flatMap (_ => b)).transact(txctr).unsafeRunSync()
  }

  def insertAll(dao: ChatHistoryDao): Unit = {
    // FIXME: Copy files
    StopWatch.measureAndCall {
      log.info("Starting insertAll")
      for (ds <- dao.datasets) {
        val myself1 = dao.myself(ds.uuid)

        StopWatch.measureAndCall {
          log.info(s"Inserting $ds")
          var query: ConnectionIO[_] = queries.datasets.insert(ds)

          for (u <- dao.users(ds.uuid)) {
            query = query flatMap (_ => queries.users.insert(u, u == myself1))
          }

          for (c <- dao.chats(ds.uuid)) {
            query = query flatMap (_ => queries.chats.insert(c))

            // TODO: Iterate over smaller subset?
            for (m <- dao.lastMessages(c, c.msgCount + 1)) {
              val (rm, rcOption, rrtOption) = Raws.fromMessage(ds.uuid, c.id, m)
              query = query flatMap (_ => queries.rawMessages.insert(rm))

              // Content
              for (rc <- rcOption) {
                query = query flatMap (_ => queries.rawContent.insert(rc, ds.uuid, c.id, m.id))
              }

              // RichText
              for ((rrt, rrtEls) <- rrtOption) {
                val rrtQuery = for {
                  rrtId <- queries.rawRichText.insert(rrt, ds.uuid, c.id, m.id)
                  combined <- (for (rrtEl <- rrtEls) yield {
                    queries.rawRichTextElements.insert(rrtEl, rrtId)
                  }) reduce ((a, b) => a flatMap (_ => b))
                } yield combined
                query = query flatMap (_ => rrtQuery)
              }
            }
          }

          query.transact(txctr).unsafeRunSync()
        }((_, t) => log.info(s"Dataset inserted in $t ms"))

        // Sanity checks
        StopWatch.measureAndCall {
          log.info(s"Running sanity checks on $ds")
          val ds2Option = datasets.find(_.uuid == ds.uuid)
          assert(
            ds2Option.isDefined && ds == ds2Option.get,
            s"dataset differs:\nWas    $ds\nBecame ${ds2Option getOrElse "<none>"}")
          assert(myself1 == myself(ds.uuid), s"'myself' differs:\nWas    $myself1\nBecame ${myself(ds.uuid)}")
          assert(
            dao.users(ds.uuid) == users(ds.uuid),
            s"Users differ:\nWas    ${dao.users(ds.uuid)}\nBecame ${users(ds.uuid)}")
          val chats1 = dao.chats(ds.uuid)
          val chats2 = chats(ds.uuid)
          assert(chats1.size == chats2.size, s"Chat size differs:\nWas    ${chats1.size}\nBecame ${chats2.size}")
          for (((c1, c2), i) <- chats1.zip(chats2).zipWithIndex) {
            StopWatch.measureAndCall {
              log.info(s"Checking chat '${c1.nameOption.getOrElse("")}' with ${c1.msgCount} messages")
              assert(c1 == c2, s"Chat #$i differs:\nWas    $c1\nBecame $c2")
              val messages1 = dao.lastMessages(c1, c1.msgCount + 1)
              val messages2 = lastMessages(c2, c2.msgCount + 1)
              assert(
                messages1.size == messages1.size,
                s"Messages size for chat $c1 (#$i) differs:\nWas    ${messages1.size}\nBecame ${messages2.size}")
              for (((m1, m2), j) <- messages1.zip(messages2).zipWithIndex) {
                assert(m1 == m2, s"Message #$j for chat $c1 (#$i) differs:\nWas    $m1\nBecame $m2")
              }
            }((_, t) => log.info(s"Chat checked in $t ms"))
          }
        }((_, t) => log.info(s"Dataset checked in $t ms"))
      }
    }((_, t) => log.info(s"All done in $t ms"))
  }

  override def close(): Unit = {
    IoUtils.closeWithoutThrowing(txctr.kernel)
  }

  private object queries {
    object datasets {
      private val colsFr = fr"uuid, alias, source_type"

      lazy val selectAll =
        (fr"SELECT" ++ colsFr ++ fr"FROM datasets").query[Dataset].to[Seq]

      def insert(ds: Dataset) =
        (fr"INSERT INTO datasets (" ++ colsFr ++ fr") VALUES (${ds.uuid}, ${ds.alias}, ${ds.sourceType})").update.run
    }

    object users {
      private val colsFr = fr"ds_uuid, id, first_name, last_name, username, phone_number, last_seen_time"
      private val selectAllFr = fr"SELECT" ++ colsFr ++ fr"FROM users"
      private def selectFr(dsUuid: UUID) = selectAllFr ++ fr"WHERE ds_uuid = $dsUuid"

      def selectAll(dsUuid: UUID) =
        selectFr(dsUuid).query[User].to[Seq]

      def selectMyself(dsUuid: UUID) =
        (selectFr(dsUuid) ++ fr"AND is_myself = true").query[User].unique

      def selectInterlocutorIds(chatId: Long) =
        sql"SELECT DISTINCT from_id FROM messages WHERE chat_id = $chatId".query[Long].to[Seq]

      def insert(u: User, isMyself: Boolean) =
        (fr"INSERT INTO users (" ++ colsFr ++ fr", is_myself) VALUES ("
          ++ fr"${u.dsUuid}, ${u.id}, ${u.firstNameOption}, ${u.lastNameOption}, ${u.usernameOption},"
          ++ fr"${u.phoneNumberOption}, ${u.lastSeenTimeOption}, ${isMyself}"
          ++ fr")").update.run
    }

    object chats {
      private val colsFr = fr"ds_uuid, id, name, type, img_path"
      private val msgCountFr = fr"(SELECT COUNT(*) FROM messages m WHERE m.chat_id = c.id) AS msg_count"

      def selectAll(dsUuid: UUID) =
        (fr"SELECT" ++ colsFr ++ fr"," ++ msgCountFr ++ fr"FROM chats c WHERE c.ds_uuid = $dsUuid").query[Chat].to[Seq]

      def insert(c: Chat) =
        (fr"INSERT INTO chats (" ++ colsFr ++ fr") VALUES ("
          ++ fr"${c.dsUuid}, ${c.id}, ${c.nameOption}, ${c.tpe}, ${c.imgPathOption}"
          ++ fr")").update.run
    }

    object rawMessages {
      private val colsFr = Fragment.const(s"""|ds_uuid, chat_id, id, message_type, time, edit_time, from_name,
                                              |from_id, forward_from_name, reply_to_message_id, title, members,
                                              |duration_sec, discard_reason, pinned_message_id,
                                              |path, width, height""".stripMargin)
      private val selectAllFr = fr"SELECT" ++ colsFr ++ fr"FROM messages"

      private def selectAllByChatFr(dsUuid: UUID, chatId: Long) =
        selectAllFr ++ fr"WHERE ds_uuid = $dsUuid AND chat_id = $chatId"

      private def orderByTimeDescLimit(limit: Int) = fr"ORDER BY time DESC, id DESC LIMIT ${limit}"

      def selectOption(chat: Chat, id: Long) =
        (selectAllByChatFr(chat.dsUuid, chat.id) ++ fr"AND id = ${id}").query[RawMessage].option

      def selectLastInversed(chat: Chat, limit: Int) =
        (selectAllByChatFr(chat.dsUuid, chat.id) ++ orderByTimeDescLimit(limit)).query[RawMessage].to[IndexedSeq]

      def selectBeforeInversed(chat: Chat, m: Message, limit: Int) =
        (selectAllByChatFr(chat.dsUuid, chat.id)
          ++ fr"AND time < ${m.time} AND id < ${m.id}"
          ++ orderByTimeDescLimit(limit)).query[RawMessage].to[IndexedSeq]

      def insert(m: RawMessage): ConnectionIO[Int] =
        (fr"INSERT INTO messages (" ++ colsFr ++ fr") VALUES ("
          ++ fr"${m.dsUuid}, ${m.chatId}, ${m.id}, ${m.messageType}, ${m.time}, ${m.editTimeOption},"
          ++ fr"${m.fromNameOption}, ${m.fromId}, ${m.forwardFromNameOption}, ${m.replyToMessageIdOption},"
          ++ fr"${m.titleOption}, ${m.members}, ${m.durationSecOption}, ${m.discardReasonOption},"
          ++ fr"${m.pinnedMessageIdOption}, ${m.pathOption}, ${m.widthOption}, ${m.heightOption}"
          ++ fr")").update.run
    }

    object rawRichText {
      def insert(rrt: RawRichText, dsUuid: UUID, chatId: Long, messageId: Long): ConnectionIO[Long] =
        (fr"INSERT INTO messages_text(ds_uuid, chat_id, message_id, plaintext) VALUES ("
          ++ fr"${dsUuid}, ${chatId}, ${messageId}, ${rrt.plaintext}"
          ++ fr")").update.withUniqueGeneratedKeys[Long]("id")

      def selectId(dsUuid: UUID, chatId: Long, messageId: Long): ConnectionIO[Option[Long]] =
        (fr"SELECT id FROM messages_text"
          ++ fr"WHERE ds_uuid = ${dsUuid} AND chat_id = ${chatId} AND message_id = ${messageId}").query[Long].option
    }

    object rawRichTextElements {
      private val colsNoIdentityFr = fr"element_type, text, href, hidden, language"

      def select(richTextId: Long) =
        (fr"SELECT" ++ colsNoIdentityFr ++ fr"FROM messages_text_element"
          ++ fr"WHERE text_id = ${richTextId}"
          ++ fr"ORDER BY id").query[RawRichTextElement].to[Seq]

      def insert(rrte: RawRichTextElement, textId: Long) =
        (fr"INSERT INTO messages_text_element(text_id," ++ colsNoIdentityFr ++ fr") VALUES ("
          ++ fr"${textId}, ${rrte.elementType}, ${rrte.text},"
          ++ fr"${rrte.hrefOption}, ${rrte.hiddenOption}, ${rrte.languageOption}"
          ++ fr")").update.withUniqueGeneratedKeys[Long]("id")
    }

    object rawContent {
      private val colsNoIdentityFr =
        Fragment.const(
          s"""|element_type, path, thumbnail_path, emoji, width, height, mime_type, title, performer, lat, lon,
              |duration_sec, poll_question, first_name, last_name, phone_number, vcard_path""".stripMargin)

      def select(dsUuid: UUID, chatId: Long, messageId: Long): ConnectionIO[Option[RawContent]] =
        (fr"SELECT" ++ colsNoIdentityFr ++ fr"FROM messages_content"
          ++ fr"WHERE ds_uuid = ${dsUuid} AND chat_id = ${chatId} AND message_id = ${messageId}")
          .query[RawContent]
          .option

      def insert(rc: RawContent, dsUuid: UUID, chatId: Long, messageId: Long): ConnectionIO[Long] =
        (fr"INSERT INTO messages_content(ds_uuid, chat_id, message_id, " ++ colsNoIdentityFr ++ fr") VALUES ("
          ++ fr"${dsUuid}, ${chatId}, ${messageId},"
          ++ fr"${rc.elementType}, ${rc.pathOption}, ${rc.thumbnailPathOption}, ${rc.emojiOption}, ${rc.widthOption},"
          ++ fr"${rc.heightOption}, ${rc.mimeTypeOption}, ${rc.titleOption}, ${rc.performerOption}, ${rc.latOption},"
          ++ fr"${rc.lonOption}, ${rc.durationSecOption}, ${rc.pollQuestionOption}, ${rc.firstNameOption},"
          ++ fr" ${rc.lastNameOption}, ${rc.phoneNumberOption}, ${rc.vcardPathOption}"
          ++ fr")").update.withUniqueGeneratedKeys[Long]("id")
    }
  }

  object Raws {
    def toMessage(rm: RawMessage): Message = {
      val textOption: Option[RichText] =
        queries.rawRichText
          .selectId(rm.dsUuid, rm.chatId, rm.id)
          .flatMap {
            // For some reason we do need explicit type ascriptions
            case Some(rrtId) =>
              queries.rawRichTextElements
                .select(rrtId)
                .map[Option[RichText]](els => Some(RichText(components = els map toRichTextElement)))
            case None =>
              connection.pure[Option[RichText]](None)
          }
          .transact(txctr)
          .unsafeRunSync()
      rm.messageType match {
        case "regular" =>
          val contentOption: Option[Content] =
            queries.rawContent.select(rm.dsUuid, rm.chatId, rm.id).transact(txctr).unsafeRunSync() map toContent
          Message.Regular(
            id                     = rm.id,
            time                   = rm.time,
            editTimeOption         = rm.editTimeOption,
            fromNameOption         = rm.fromNameOption,
            fromId                 = rm.fromId,
            forwardFromNameOption  = rm.forwardFromNameOption,
            replyToMessageIdOption = rm.replyToMessageIdOption,
            textOption             = textOption,
            contentOption          = contentOption
          )
        case "service_phone_call" =>
          Message.Service.PhoneCall(
            id                  = rm.id,
            time                = rm.time,
            fromNameOption      = rm.fromNameOption,
            fromId              = rm.fromId,
            textOption          = textOption,
            durationSecOption   = rm.durationSecOption,
            discardReasonOption = rm.discardReasonOption
          )
        case "service_pin_message" =>
          Message.Service.PinMessage(
            id             = rm.id,
            time           = rm.time,
            fromNameOption = rm.fromNameOption,
            fromId         = rm.fromId,
            textOption     = textOption,
            messageId      = rm.pinnedMessageIdOption.get
          )
        case "service_clear_history" =>
          Message.Service.ClearHistory(
            id             = rm.id,
            time           = rm.time,
            fromNameOption = rm.fromNameOption,
            fromId         = rm.fromId,
            textOption     = textOption,
          )
        case "service_edit_photo" =>
          Message.Service.EditPhoto(
            id             = rm.id,
            time           = rm.time,
            fromNameOption = rm.fromNameOption,
            fromId         = rm.fromId,
            textOption     = textOption,
            pathOption     = rm.pathOption,
            widthOption    = rm.widthOption,
            heightOption   = rm.heightOption
          )
        case "service_group_create" =>
          Message.Service.Group.Create(
            id             = rm.id,
            time           = rm.time,
            fromNameOption = rm.fromNameOption,
            fromId         = rm.fromId,
            textOption     = textOption,
            title          = rm.titleOption.get,
            members        = rm.members
          )
        case "service_invite_group_members" =>
          Message.Service.Group.InviteMembers(
            id             = rm.id,
            time           = rm.time,
            fromNameOption = rm.fromNameOption,
            fromId         = rm.fromId,
            textOption     = textOption,
            members        = rm.members
          )
        case "service_group_remove_members" =>
          Message.Service.Group.RemoveMembers(
            id             = rm.id,
            time           = rm.time,
            fromNameOption = rm.fromNameOption,
            fromId         = rm.fromId,
            textOption     = textOption,
            members        = rm.members
          )
      }
    }

    def toRichTextElement(r: RawRichTextElement): RichText.Element = {
      r.elementType match {
        case "plain" =>
          RichText.Plain(text = r.text)
        case "bold" =>
          RichText.Bold(text = r.text)
        case "italic" =>
          RichText.Italic(text = r.text)
        case "link" =>
          RichText.Link(text = r.text, href = r.hrefOption.get, hidden = r.hiddenOption.get)
        case "prefmt_inline" =>
          RichText.PrefmtInline(text = r.text)
        case "prefmt_block" =>
          RichText.PrefmtBlock(text = r.text, languageOption = r.languageOption)
      }
    }

    def toContent(rc: RawContent): Content = {
      rc.elementType match {
        case "sticker" =>
          Content.Sticker(
            pathOption          = rc.pathOption,
            thumbnailPathOption = rc.thumbnailPathOption,
            emojiOption         = rc.emojiOption,
            widthOption         = rc.widthOption,
            heightOption        = rc.heightOption
          )
        case "photo" =>
          Content.Photo(
            pathOption = rc.pathOption,
            width      = rc.widthOption.get,
            height     = rc.heightOption.get,
          )
        case "voice_message" =>
          Content.VoiceMsg(
            pathOption        = rc.pathOption,
            mimeTypeOption    = rc.mimeTypeOption,
            durationSecOption = rc.durationSecOption
          )
        case "video_message" =>
          Content.VideoMsg(
            pathOption          = rc.pathOption,
            thumbnailPathOption = rc.thumbnailPathOption,
            mimeTypeOption      = rc.mimeTypeOption,
            durationSecOption   = rc.durationSecOption,
            width               = rc.widthOption.get,
            height              = rc.heightOption.get
          )
        case "animation" =>
          Content.Animation(
            pathOption          = rc.pathOption,
            thumbnailPathOption = rc.thumbnailPathOption,
            mimeTypeOption      = rc.mimeTypeOption,
            durationSecOption   = rc.durationSecOption,
            width               = rc.widthOption.get,
            height              = rc.heightOption.get
          )
        case "file" =>
          Content.File(
            pathOption          = rc.pathOption,
            thumbnailPathOption = rc.thumbnailPathOption,
            mimeTypeOption      = rc.mimeTypeOption,
            titleOption         = rc.titleOption,
            performerOption     = rc.performerOption,
            durationSecOption   = rc.durationSecOption,
            widthOption         = rc.widthOption,
            heightOption        = rc.heightOption
          )
        case "location" =>
          Content.Location(
            lat               = rc.latOption.get,
            lon               = rc.lonOption.get,
            durationSecOption = rc.durationSecOption
          )
        case "poll" =>
          Content.Poll(
            question = rc.pollQuestionOption.get
          )
        case "shared_contact" =>
          Content.SharedContact(
            firstNameOption   = rc.firstNameOption,
            lastNameOption    = rc.lastNameOption,
            phoneNumberOption = rc.phoneNumberOption,
            vcardPathOption   = rc.vcardPathOption
          )
      }
    }

    def fromMessage(
        dsUuid: UUID,
        chatId: Long,
        m: Message
    ): (RawMessage, Option[RawContent], Option[(RawRichText, Seq[RawRichTextElement])]) = {
      val rawRichTextOption = m.textOption map fromRichText
      val template = RawMessage(
        dsUuid                 = dsUuid,
        chatId                 = chatId,
        id                     = m.id,
        messageType            = "",
        time                   = m.time,
        editTimeOption         = None,
        fromNameOption         = m.fromNameOption,
        fromId                 = m.fromId,
        forwardFromNameOption  = None,
        replyToMessageIdOption = None,
        titleOption            = None,
        members                = Seq.empty,
        durationSecOption      = None,
        discardReasonOption    = None,
        pinnedMessageIdOption  = None,
        pathOption             = None,
        widthOption            = None,
        heightOption           = None,
      )

      val (rawMessage: RawMessage, rawContentOption: Option[RawContent]) = m match {
        case m: Message.Regular =>
          val rawContentOption = m.contentOption map fromContent
          template.copy(
            messageType            = "regular",
            editTimeOption         = m.editTimeOption,
            forwardFromNameOption  = m.forwardFromNameOption,
            replyToMessageIdOption = m.replyToMessageIdOption
          ) -> rawContentOption
        case m: Message.Service.PhoneCall =>
          template.copy(
            messageType         = "service_phone_call",
            durationSecOption   = m.durationSecOption,
            discardReasonOption = m.discardReasonOption
          ) -> None
        case m: Message.Service.PinMessage =>
          template.copy(
            messageType           = "service_pin_message",
            pinnedMessageIdOption = Some(m.messageId),
          ) -> None
        case m: Message.Service.ClearHistory =>
          template.copy(
            messageType = "service_clear_history",
          ) -> None
        case m: Message.Service.EditPhoto =>
          template.copy(
            messageType  = "service_edit_photo",
            pathOption   = m.pathOption,
            widthOption  = m.widthOption,
            heightOption = m.heightOption
          ) -> None
        case m: Message.Service.Group.Create =>
          template.copy(
            messageType = "service_group_create",
            titleOption = Some(m.title),
            members     = m.members
          ) -> None
        case m: Message.Service.Group.InviteMembers =>
          template.copy(
            messageType = "service_invite_group_members",
            members     = m.members
          ) -> None
        case m: Message.Service.Group.RemoveMembers =>
          template.copy(
            messageType = "service_group_remove_members",
            members     = m.members
          ) -> None
      }
      (rawMessage, rawContentOption, rawRichTextOption)
    }

    def fromRichText(t: RichText): (RawRichText, Seq[RawRichTextElement]) = {
      val rawEls: Seq[RawRichTextElement] = t.components.map { el =>
        val template = RawRichTextElement(
          elementType    = "",
          text           = el.text,
          hrefOption     = None,
          hiddenOption   = None,
          languageOption = None
        )
        el match {
          case el: RichText.Plain  => template.copy(elementType = "plain")
          case el: RichText.Bold   => template.copy(elementType = "bold")
          case el: RichText.Italic => template.copy(elementType = "italic")
          case el: RichText.Link =>
            template.copy(
              elementType  = "link",
              hrefOption   = Some(el.href),
              hiddenOption = Some(el.hidden)
            )
          case el: RichText.PrefmtInline => template.copy(elementType = "prefmt_inline")
          case el: RichText.PrefmtBlock =>
            template.copy(
              elementType    = "prefmt_block",
              languageOption = el.languageOption
            )
        }
      }
      (RawRichText(t.plainSearchableString), rawEls)
    }

    def fromContent(c: Content): RawContent = {
      val template = RawContent(
        elementType         = "",
        pathOption          = None,
        thumbnailPathOption = None,
        emojiOption         = None,
        widthOption         = None,
        heightOption        = None,
        mimeTypeOption      = None,
        titleOption         = None,
        performerOption     = None,
        latOption           = None,
        lonOption           = None,
        durationSecOption   = None,
        pollQuestionOption  = None,
        firstNameOption     = None,
        lastNameOption      = None,
        phoneNumberOption   = None,
        vcardPathOption     = None
      )
      c match {
        case c: Content.Sticker =>
          template.copy(
            elementType         = "sticker",
            pathOption          = c.pathOption,
            thumbnailPathOption = c.thumbnailPathOption,
            emojiOption         = c.emojiOption,
            widthOption         = c.widthOption,
            heightOption        = c.heightOption
          )
        case c: Content.Photo =>
          template.copy(
            elementType  = "photo",
            pathOption   = c.pathOption,
            widthOption  = Some(c.width),
            heightOption = Some(c.height)
          )
        case c: Content.VoiceMsg =>
          template.copy(
            elementType       = "voice_message",
            pathOption        = c.pathOption,
            mimeTypeOption    = c.mimeTypeOption,
            durationSecOption = c.durationSecOption
          )
        case c: Content.VideoMsg =>
          template.copy(
            elementType         = "video_message",
            pathOption          = c.pathOption,
            thumbnailPathOption = c.thumbnailPathOption,
            mimeTypeOption      = c.mimeTypeOption,
            durationSecOption   = c.durationSecOption,
            widthOption         = Some(c.width),
            heightOption        = Some(c.height)
          )
        case c: Content.Animation =>
          template.copy(
            elementType         = "animation",
            pathOption          = c.pathOption,
            thumbnailPathOption = c.thumbnailPathOption,
            mimeTypeOption      = c.mimeTypeOption,
            durationSecOption   = c.durationSecOption,
            widthOption         = Some(c.width),
            heightOption        = Some(c.height)
          )
        case c: Content.File =>
          template.copy(
            elementType         = "file",
            pathOption          = c.pathOption,
            thumbnailPathOption = c.thumbnailPathOption,
            mimeTypeOption      = c.mimeTypeOption,
            titleOption         = c.titleOption,
            performerOption     = c.performerOption,
            durationSecOption   = c.durationSecOption,
            widthOption         = c.widthOption,
            heightOption        = c.heightOption
          )
        case c: Content.Location =>
          template.copy(
            elementType       = "location",
            latOption         = Some(c.lat),
            lonOption         = Some(c.lon),
            durationSecOption = c.durationSecOption
          )
        case c: Content.Poll =>
          template.copy(
            elementType        = "poll",
            pollQuestionOption = Some(c.question)
          )
        case c: Content.SharedContact =>
          template.copy(
            elementType       = "shared_contact",
            firstNameOption   = c.firstNameOption,
            lastNameOption    = c.lastNameOption,
            phoneNumberOption = c.phoneNumberOption,
            vcardPathOption   = c.vcardPathOption
          )
      }
    }
  }
}

object H2ChatHistoryDao {

  //
  // "Raw" case classes, more closely matching DB structure
  //

  /** [[Message]] class with all the inlined fields, whose type is determined by `messageType` */
  case class RawMessage(
      dsUuid: UUID,
      chatId: Long,
      id: Long,
      messageType: String,
      time: DateTime,
      editTimeOption: Option[DateTime],
      fromNameOption: Option[String],
      fromId: Long,
      forwardFromNameOption: Option[String],
      replyToMessageIdOption: Option[Long],
      titleOption: Option[String],
      members: Seq[String],
      durationSecOption: Option[Int],
      discardReasonOption: Option[String],
      pinnedMessageIdOption: Option[Long],
      pathOption: Option[String],
      widthOption: Option[Int],
      heightOption: Option[Int]
  )

  case class RawRichText(
      plaintext: String
  )

  case class RawRichTextElement(
      elementType: String,
      text: String,
      hrefOption: Option[String],
      hiddenOption: Option[Boolean],
      languageOption: Option[String]
  )

  case class RawContent(
      elementType: String,
      pathOption: Option[String],
      thumbnailPathOption: Option[String],
      emojiOption: Option[String],
      widthOption: Option[Int],
      heightOption: Option[Int],
      mimeTypeOption: Option[String],
      titleOption: Option[String],
      performerOption: Option[String],
      latOption: Option[BigDecimal],
      lonOption: Option[BigDecimal],
      durationSecOption: Option[Int],
      pollQuestionOption: Option[String],
      firstNameOption: Option[String],
      lastNameOption: Option[String],
      phoneNumberOption: Option[String],
      vcardPathOption: Option[String]
  )

  //
  // Doobie serialization helpers
  //

  import javasql._
  implicit protected val dateTimeMeta: Meta[DateTime] =
    Meta[Timestamp].imap(ts => new DateTime(ts.getTime))(dt => new Timestamp(dt.getMillis))

  implicit val chatTypeFromString: Get[ChatType] = Get[String].tmap {
    case x if x == ChatType.Personal.name     => ChatType.Personal
    case x if x == ChatType.PrivateGroup.name => ChatType.PrivateGroup
  }
  implicit val chatTypeToString: Put[ChatType] = Put[String].tcontramap(_.name)

  private val ArraySeparator = ";;;"
  implicit val stringSeqFromString: Get[Seq[String]] = Get[String].tmap(s => s.split(ArraySeparator))
  implicit val stringSeqToString:   Put[Seq[String]] = Put[String].tcontramap(ss => ss.mkString(ArraySeparator))
}
