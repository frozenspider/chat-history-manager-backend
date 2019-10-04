package org.fs.chm.loader

import java.io.File
import java.io.FileNotFoundException

import scala.collection.immutable.ListMap

import com.github.nscala_time.time.Imports.DateTime
import org.fs.chm.dao._
import org.json4s._
import org.json4s.jackson.JsonMethods

class TelegramDataLoader extends DataLoader {
  implicit private val formats: Formats = DefaultFormats.withLong.withBigDecimal

  override def loadDataInner(path: File): ChatHistoryDao = {
    implicit val dummyTracker = new FieldUsageTracker
    val resultJsonFile: File = new File(path, "result.json")
    if (!resultJsonFile.exists()) throw new FileNotFoundException("result.json not found in " + path.getAbsolutePath)
    val parsed = JsonMethods.parse(resultJsonFile)
    val contacts = for {
      contact <- getCheckedField[Seq[JValue]](parsed, "contacts", "list")
    } yield parseContact(contact)

    val chatsWithMessages = for {
      chat <- getCheckedField[Seq[JValue]](parsed, "chats", "list")
      if (getCheckedField[String](chat, "type") != "saved_messages")
    } yield {
      val messagesRes = for {
        message <- getCheckedField[IndexedSeq[JValue]](chat, "messages")
        if getCheckedField[String](message, "type") == "message"
        // FIXME: Service messages, phone calls
      } yield parseMessage(message)

      val chatRes = parseChat(chat, messagesRes.size)
      (chatRes, messagesRes)
    }
    val chatsWithMessagesLM = ListMap(chatsWithMessages: _*)

    new EagerChatHistoryDao(contacts = contacts, chatsWithMessages = chatsWithMessagesLM)
  }

  private def parseContact(jv: JValue): Contact = {
    implicit val tracker = new FieldUsageTracker
    tracker.ensuringUsage(jv) {
      Contact(
        id                = getCheckedField[Long](jv, "user_id"),
        firstNameOption   = getStringOpt(jv, "first_name", true),
        lastNameOption    = getStringOpt(jv, "last_name", true),
        phoneNumberOption = getStringOpt(jv, "phone_number", true),
        // TODO: timezone?
        lastSeenDateOption = stringToDateTimeOpt(getCheckedField[String](jv, "date"))
      )
    }
  }

  private def parseMessage(jv: JValue): Message = {
    implicit val tracker = new FieldUsageTracker
    tracker.markUsed("type")
    tracker.markUsed("via_bot") // Ignored
    tracker.ensuringUsage(jv) {
      Message.Regular(
        id                     = getCheckedField[Long](jv, "id"),
        date                   = stringToDateTimeOpt(getCheckedField[String](jv, "date")).get,
        editDateOption         = stringToDateTimeOpt(getCheckedField[String](jv, "edited")),
        fromName               = getCheckedField[String](jv, "from"),
        fromId                 = getCheckedField[Long](jv, "from_id"),
        forwardFromNameOption  = getStringOpt(jv, "forwarded_from", false),
        replyToMessageIdOption = getFieldOpt[Long](jv, "reply_to_message_id", false),
        textOption             = parseText(jv),
        contentOption          = ContentParser.parseContentOption(jv)
      )
    }
  }

  private def parseText(jv: JValue)(implicit tracker: FieldUsageTracker): Option[String] = {
    // FIXME
    tracker.markUsed("text")
    Some((jv \ "text").toString)
  }

  private object ContentParser {
    def parseContentOption(jv: JValue)(implicit tracker: FieldUsageTracker): Option[Content] = {
      val mediaTypeOption     = getFieldOpt[String](jv, "media_type", false)
      val photoOption         = getFieldOpt[String](jv, "photo", false)
      val fileOption          = getFieldOpt[String](jv, "file", false)
      val locPresent          = (jv \ "location_information") != JNothing
      val pollQuestionPresent = (jv \ "poll" \ "question") != JNothing
      val contactInfoPresent  = (jv \ "contact_information") != JNothing
      (mediaTypeOption, photoOption, fileOption, locPresent, pollQuestionPresent, contactInfoPresent) match {
        case (None, None, None, false, false, false)                     => None
        case (Some("sticker"), None, Some(_), false, false, false)       => Some(parseSticker(jv))
        case (Some("animation"), None, Some(_), false, false, false)     => Some(parseAnimation(jv))
        case (Some("video_message"), None, Some(_), false, false, false) => Some(parseVideoMsg(jv))
        case (Some("voice_message"), None, Some(_), false, false, false) => Some(parseVoiceMsg(jv))
        case (Some("video_file"), None, Some(_), false, false, false)    => Some(parseFile(jv))
        case (Some("audio_file"), None, Some(_), false, false, false)    => Some(parseFile(jv))
        case (None, Some(_), None, false, false, false)                  => Some(parsePhoto(jv))
        case (None, None, Some(_), false, false, false)                  => Some(parseFile(jv))
        case (None, None, None, true, false, false)                      => Some(parseLocation(jv))
        case (None, None, None, false, true, false)                      => Some(parsePoll(jv))
        case (None, None, None, false, false, true)                      => Some(parseSharedContact(jv))
        case _ =>
          throw new IllegalArgumentException(s"Couldn't determine content type for '$jv'")
      }
    }

    private def parseSticker(jv: JValue)(implicit tracker: FieldUsageTracker): Content.Sticker = {
      Content.Sticker(
        pathOption          = getStringOpt(jv, "file", true),
        thumbnailPathOption = getStringOpt(jv, "thumbnail", true),
        emojiOption         = getStringOpt(jv, "sticker_emoji", false),
        widthOption         = getFieldOpt[Int](jv, "width", false),
        heightOption        = getFieldOpt[Int](jv, "height", false)
      )
    }

    private def parsePhoto(jv: JValue)(implicit tracker: FieldUsageTracker): Content.Photo = {
      Content.Photo(
        pathOption = getStringOpt(jv, "photo", true),
        width      = getCheckedField[Int](jv, "width"),
        height     = getCheckedField[Int](jv, "height"),
      )
    }

    private def parseAnimation(jv: JValue)(implicit tracker: FieldUsageTracker): Content.Animation = {
      Content.Animation(
        pathOption          = getStringOpt(jv, "file", true),
        thumbnailPathOption = getStringOpt(jv, "thumbnail", true),
        mimeTypeOption      = Some(getCheckedField[String](jv, "mime_type")),
        durationSecOption   = getFieldOpt[Int](jv, "duration_seconds", false),
        width               = getCheckedField[Int](jv, "width"),
        height              = getCheckedField[Int](jv, "height"),
      )
    }

    private def parseVoiceMsg(jv: JValue)(implicit tracker: FieldUsageTracker): Content.VoiceMsg = {
      Content.VoiceMsg(
        pathOption        = getStringOpt(jv, "file", true),
        mimeTypeOption    = Some(getCheckedField[String](jv, "mime_type")),
        durationSecOption = getFieldOpt[Int](jv, "duration_seconds", false),
      )
    }

    private def parseVideoMsg(jv: JValue)(implicit tracker: FieldUsageTracker): Content.VideoMsg = {
      Content.VideoMsg(
        pathOption          = getStringOpt(jv, "file", true),
        thumbnailPathOption = getStringOpt(jv, "thumbnail", true),
        mimeTypeOption      = Some(getCheckedField[String](jv, "mime_type")),
        durationSecOption   = getFieldOpt[Int](jv, "duration_seconds", false),
        width               = getCheckedField[Int](jv, "width"),
        height              = getCheckedField[Int](jv, "height"),
      )
    }

    private def parseFile(jv: JValue)(implicit tracker: FieldUsageTracker): Content.File = {
      Content.File(
        pathOption          = getStringOpt(jv, "file", true),
        thumbnailPathOption = getStringOpt(jv, "thumbnail", false),
        mimeTypeOption      = getStringOpt(jv, "mime_type", true),
        titleOption         = getStringOpt(jv, "title", false),
        performerOption     = getStringOpt(jv, "performer", false),
        durationSecOption   = getFieldOpt[Int](jv, "duration_seconds", false),
        widthOption         = getFieldOpt[Int](jv, "width", false),
        heightOption        = getFieldOpt[Int](jv, "height", false)
      )
    }

    private def parseLocation(jv: JValue)(implicit tracker: FieldUsageTracker): Content.Location = {
      Content.Location(
        lat                   = getCheckedField[BigDecimal](jv, "location_information", "latitude"),
        lon                   = getCheckedField[BigDecimal](jv, "location_information", "longitude"),
        liveDurationSecOption = getFieldOpt[Int](jv, "live_location_period_seconds", false)
      )
    }

    private def parsePoll(jv: JValue)(implicit tracker: FieldUsageTracker): Content.Poll = {
      Content.Poll(
        question = getCheckedField[String](jv, "poll", "question")
      )
    }

    private def parseSharedContact(jv: JValue)(implicit tracker: FieldUsageTracker): Content.SharedContact = {
      val ci = getRawField(jv, "contact_information", true)
      Content.SharedContact(
        firstNameOption   = getStringOpt(ci, "first_name", true),
        lastNameOption    = getStringOpt(ci, "last_name", true),
        phoneNumberOption = getStringOpt(ci, "phone_number", true),
        vcardPathOption   = getStringOpt(jv, "contact_vcard", false)
      )
    }
  }

  private def parseChat(jv: JValue, msgNum: Int): Chat = {
    implicit val tracker = new FieldUsageTracker
    tracker.markUsed("messages")
    tracker.ensuringUsage(jv) {
      Chat(
        id         = getCheckedField[Long](jv, "id"),
        nameOption = getCheckedField[Option[String]](jv, "name"),
        tpe = getCheckedField[String](jv, "type") match {
          case "personal_chat" => ChatType.Personal
          case "private_group" => ChatType.PrivateGroup
          case s               => throw new IllegalArgumentException("Illegal format, unknown chat type '$s'")
        },
        msgNum = msgNum
      )
    }
  }

  private def stringToDateTimeOpt(s: String): Option[DateTime] = {
    DateTime.parse(s) match {
      case dt if dt.year.get == 1970 => None // TG puts minimum timestamp in place of absent
      case other                     => Some(other)
    }
  }

  private def getRawField(jv: JValue, fieldName: String, mustPresent: Boolean)(
      implicit tracker: FieldUsageTracker): JValue = {
    val res = jv \ fieldName
    tracker.markUsed(fieldName)
    if (mustPresent) require(res != JNothing, s"Incompatible format! Field '$fieldName' not found in $jv")
    res
  }

  private def getFieldOpt[A](jv: JValue, fieldName: String, mustPresent: Boolean)(
      implicit formats: Formats,
      mf: scala.reflect.Manifest[A],
      tracker: FieldUsageTracker): Option[A] = {
    getRawField(jv, fieldName, mustPresent).extractOpt[A]
  }

  private def getStringOpt[A](jv: JValue, fieldName: String, mustPresent: Boolean)(
      implicit formats: Formats,
      tracker: FieldUsageTracker): Option[String] = {
    val res = jv \ fieldName
    tracker.markUsed(fieldName)
    if (mustPresent) require(res != JNothing, s"Incompatible format! Field '$fieldName' not found in $jv")
    res.extractOpt[String] match {
      case Some("")                                                                 => None
      case Some("(File not included. Change data exporting settings to download.)") => None
      case other                                                                    => other
    }
  }

  private def getCheckedField[A](jv: JValue, fieldName: String)(implicit formats: Formats,
                                                                mf: scala.reflect.Manifest[A],
                                                                tracker: FieldUsageTracker): A = {
    getRawField(jv, fieldName, true).extract[A]
  }

  private def getCheckedField[A](jv: JValue, fn1: String, fn2: String)(implicit formats: Formats,
                                                                       mf: scala.reflect.Manifest[A],
                                                                       tracker: FieldUsageTracker): A = {
    val res = jv \ fn1 \ fn2
    require(res != JNothing, s"Incompatible format! Path '$fn1 \\ $fn2' not found in $jv")
    tracker.markUsed(fn1)
    res.extract[A]
  }

  /** Tracks JSON fields being used and ensures that nothing is left unattended */
  class FieldUsageTracker {
    private var markedFields: Set[String] = Set.empty

    def markUsed(fieldName: String): Unit = {
      markedFields = markedFields + fieldName
    }

    def ensuringUsage[A](jv: JValue)(codeBlock: => A): A = {
      val res: A = codeBlock
      ensureUsage(jv)
      res
    }

    def ensureUsage(jv: JValue): Unit = {
      jv match {
        case JObject(children) =>
          val objFields = Set.empty ++ children.map(_._1)
          val unused    = objFields.diff(markedFields)
          if (unused.nonEmpty) {
            throw new IllegalArgumentException(s"Unused fields! $unused for ${jv.toString.take(500)}")
          }
        case _ =>
          throw new IllegalArgumentException("Not a JObject! " + jv)
      }
    }
  }
}
