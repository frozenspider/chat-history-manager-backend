package org.fs.chm.dao

import java.io.{File => JFile}
import java.util.UUID

import com.github.nscala_time.time.Imports._
import org.fs.chm.dao.Helpers._
import org.fs.chm.protobuf._
import org.fs.chm.utility.EntityUtils._
import org.fs.chm.utility.IoUtils._
import org.fs.utility.Imports._

/**
 * Everything except for messages should be pre-cached and readily available.
 * Should support equality.
 */
trait ChatHistoryDao extends AutoCloseable {
  sys.addShutdownHook(close())

  /** User-friendly name of a loaded data */
  def name: String

  def datasets: Seq[Dataset]

  /** Directory which stores eveything in the dataset. All files are guaranteed to have this as a prefix */
  def datasetRoot(dsUuid: UUID): JFile

  /** List all files referenced by entities of this dataset. Some might not exist. */
  def datasetFiles(dsUuid: UUID): Set[JFile]

  def myself(dsUuid: UUID): User

  /** Contains myself as the first element. Order must be stable. Method is expected to be fast. */
  def users(dsUuid: UUID): Seq[User]

  def userOption(dsUuid: UUID, id: Long): Option[User]

  def chats(dsUuid: UUID): Seq[ChatWithDetails]

  def chatOption(dsUuid: UUID, id: Long): Option[ChatWithDetails]

  /** Return N messages after skipping first M of them. Trivial pagination in a nutshell. */
  def scrollMessages(chat: Chat, offset: Int, limit: Int): IndexedSeq[Message]

  def firstMessages(chat: Chat, limit: Int): IndexedSeq[Message] =
    scrollMessages(chat, 0, limit)

  def lastMessages(chat: Chat, limit: Int): IndexedSeq[Message]

  /**
   * Return N messages before the given one (inclusive).
   * Message must be present, so the result would contain at least one element.
   */
  final def messagesBefore(chat: Chat, msg: Message, limit: Int): IndexedSeq[Message] =
    messagesBeforeImpl(chat, msg, limit) ensuring (seq => seq.nonEmpty && seq.size <= limit && seq.last =~= msg)

  protected def messagesBeforeImpl(chat: Chat, msg: Message, limit: Int): IndexedSeq[Message]

  /**
   * Return N messages after the given one (inclusive).
   * Message must be present, so the result would contain at least one element.
   */
  final def messagesAfter(chat: Chat, msg: Message, limit: Int): IndexedSeq[Message] =
    messagesAfterImpl(chat, msg, limit) ensuring (seq => seq.nonEmpty && seq.size <= limit && seq.head =~= msg)

  protected def messagesAfterImpl(chat: Chat, msg: Message, limit: Int): IndexedSeq[Message]

  /**
   * Return N messages between the given ones (inclusive).
   * Messages must be present, so the result would contain at least one element (if both are the same message).
   */
  final def messagesBetween(chat: Chat, msg1: Message, msg2: Message): IndexedSeq[Message] =
    messagesBetweenImpl(chat, msg1, msg2) ensuring (seq => seq.nonEmpty && seq.head =~= msg1 && seq.last =~= msg2)

  protected def messagesBetweenImpl(chat: Chat, msg1: Message, msg2: Message): IndexedSeq[Message]

  /**
   * Count messages between the given ones (exclusive, unlike messagesBetween).
   * Messages must be present.
   */
  def countMessagesBetween(chat: Chat, msg1: Message, msg2: Message): Int

  /** Returns N messages before and N at-or-after the given date */
  def messagesAroundDate(chat: Chat, date: DateTime, limit: Int): (IndexedSeq[Message], IndexedSeq[Message])

  def messageOption(chat: Chat, id: Message.SourceId): Option[Message]

  def messageOptionByInternalId(chat: Chat, id: Message.InternalId): Option[Message]

  def isMutable: Boolean = this.isInstanceOf[MutableChatHistoryDao]

  def mutable = this.asInstanceOf[MutableChatHistoryDao]

  override def close(): Unit = {}

  /** Whether given data path is the one loaded in this DAO */
  def isLoaded(dataPathRoot: JFile): Boolean
}

trait MutableChatHistoryDao extends ChatHistoryDao {
  def insertDataset(ds: Dataset): Unit

  def renameDataset(dsUuid: UUID, newName: String): Dataset

  def deleteDataset(dsUuid: UUID): Unit

  /** Shift time of all timestamps in the dataset to accommodate timezone differences */
  def shiftDatasetTime(dsUuid: UUID, hrs: Int): Unit

  /** Insert a new user. It should not yet exist */
  def insertUser(user: User, isMyself: Boolean): Unit

  /** Sets the data (names and phone only) for a user with the given `id` and `dsUuid` to the given state */
  def updateUser(user: User): Unit

  /**
   * Merge absorbed user into base user, replacing base user's names and phone.
   * Their personal chats will also be merged into one (named after the "new" user).
   */
  def mergeUsers(baseUser: User, absorbedUser: User): Unit

  /** Insert a new chat. It should not yet exist, and all users must already be inserted */
  def insertChat(dsRoot: JFile, chat: Chat): Unit

  def deleteChat(chat: Chat): Unit

  /** Insert a new message for the given chat. Internal ID will be ignored */
  def insertMessages(dsRoot: JFile, chat: Chat, msgs: Seq[Message]): Unit

  /** Don't do automatic backups on data changes until re-enabled */
  def disableBackups(): Unit

  /** Start doing backups automatically once again */
  def enableBackups(): Unit

  /** Create a backup, if enabled, otherwise do nothing */
  def backup(): Unit
}

object ChatHistoryDao {
  val Unnamed = "[unnamed]"
}

sealed trait WithId {
  def id: Long
}

case class Dataset(
    uuid: UUID,
    alias: String,
    sourceType: String
) {
  override def equals(that: Any): Boolean = that match {
    case that: Dataset => this.uuid == that.uuid
    case _             => false
  }
  override def hashCode: Int = this.uuid.hashCode
}

object Dataset {
  def createDefault(srcAlias: String, srcType: String) = Dataset(
    uuid       = UUID.randomUUID(),
    alias      = s"${srcAlias} data loaded @ " + DateTime.now().toString("yyyy-MM-dd"),
    sourceType = srcType
  )
}

sealed trait PersonInfo {
  val firstNameOption: Option[String]
  val lastNameOption: Option[String]
  val phoneNumberOption: Option[String]

  lazy val prettyNameOption: Option[String] = {
    val parts = Seq(firstNameOption, lastNameOption).yieldDefined
    if (parts.isEmpty) None else Some(parts.mkString(" ").trim)
  }

  lazy val prettyName: String =
    prettyNameOption getOrElse ChatHistoryDao.Unnamed
}

case class User(
    dsUuid: UUID,
    /** Unique within a dataset */
    id: Long,
    /** If there's no first/last name separation, everything will be in first name */
    firstNameOption: Option[String],
    lastNameOption: Option[String],
    usernameOption: Option[String],
    phoneNumberOption: Option[String]
) extends PersonInfo
    with WithId

sealed abstract class ChatType(val name: String)
object ChatType {
  case object Personal     extends ChatType("personal")
  case object PrivateGroup extends ChatType("private_group")
}

case class Chat(
    dsUuid: UUID,
    /** Unique within a dataset */
    id: Long,
    nameOption: Option[String],
    tpe: ChatType,
    imgPathOption: Option[JFile],
    memberIds: Set[Long],
    msgCount: Int
) extends WithId {
  override def toString: String = s"Chat(${nameOption.getOrElse("[unnamed]")}, ${tpe.name}})"
}

trait Searchable {
  def plainSearchableString: String
}

case class ChatWithDetails(chat: Chat,
                           lastMsgOption: Option[Message],
                           /** First element MUST be myself, the rest should be in some fixed order. */
                           members: Seq[User]) extends WithId {
  val dsUuid: UUID = chat.dsUuid

  override val id: Long = chat.id
}

//
// Rich Text
//

case class RichText(
    components: Seq[RichTextElement]
) extends Searchable {
  val plainSearchableString: String = {
    val joined = (components map (_.textOrEmptyString) mkString " ")
      .replaceAll("[\\s\\p{Cf}\n]+", " ")
      .trim
    // Adding all links to the end to enable search over hrefs/hidden links too
    val links = components.map(_.`val`.link).collect({ case Some(l) => l.href }).mkString(" ")
    joined + links
  }
}

object RichText {
  def fromPlainString(s: String): RichText =
    RichText(Seq(RichTextElement(RichTextElement.Val.Plain(RtePlain(s)))))
}

//
// Message
//

/*
 * Design goal for this section - try to reuse as many fields as possible to comfortably store
 * the whole Message hierarchy in one table.
 *
 * Same applies to Content.
 */

sealed trait Message extends Searchable {

  /**
   * Unique ID assigned to this message by a DAO storage engine, should be -1 until saved.
   * No ordering guarantees are provided in general case.
   * Might change on dataset/chat mutation operations.
   * Should NEVER be compared across different DAOs!
   */
  val internalId: Message.InternalId

  /**
   * Unique within a chat, serves as a persistent ID when merging with older/newer DB version.
   * If it's not useful for this purpose, should be empty.
   */
  val sourceIdOption: Option[Message.SourceId]
  val time: DateTime
  val fromId: Long
  val textOption: Option[RichText]

  /**
   * Should return self type, but F-bound polymorphism turns type inference into pain - so no type-level enforcement, unfortunately.
   * Just make sure subclasses have correct return type.
   */
  def withInternalId(internalId: Message.InternalId): Message

  /**
   * Should return self type, but F-bound polymorphism turns type inference into pain - so no type-level enforcement, unfortunately.
   * Just make sure subclasses have correct return type.
   */
  def withFromId(fromId: Long): Message

  /** All file paths, which might or might not exist */
  def files: Set[JFile]

  /** We can't use "super" on vals/lazy vals, so... */
  protected val plainSearchableMsgString =
    textOption map (_.plainSearchableString) getOrElse ""

  override val plainSearchableString = plainSearchableMsgString

  /** "Practical equality" that ignores internal ID, paths of files with equal content, and some unimportant fields */
  def =~=(that: Message) =
    this.withInternalId(Message.NoInternalId) == that.withInternalId(Message.NoInternalId)

  def !=~=(that: Message) = !(this =~= that)

  override def toString: String = s"Message($plainSearchableString)"
}

object Message {
  sealed trait InternalIdTag
  type InternalId = Long with InternalIdTag

  sealed trait SourceIdTag
  type SourceId = Long with SourceIdTag

  val NoInternalId: InternalId = -1L.asInstanceOf[InternalId]

  case class Regular(
      internalId: InternalId,
      sourceIdOption: Option[SourceId],
      time: DateTime,
      editTimeOption: Option[DateTime],
      fromId: Long,
      /** Excluded from `=~=` comparison */
      forwardFromNameOption: Option[String],
      replyToMessageIdOption: Option[SourceId],
      textOption: Option[RichText],
      contentOption: Option[Content]
  ) extends Message {
    override def withInternalId(internalId: Message.InternalId): Regular = copy(internalId = internalId)

    override def withFromId(fromId: Long): Regular = copy(fromId = fromId)

    override def files: Set[JFile] = {
      (contentOption match {
        case None => Set.empty[JFile]
        case Some(content) =>
          (content.`val` match {
            case Content.Val.Sticker(v)       => Set(v.path, v.thumbnailPath)
            case Content.Val.Photo(v)         => Set(v.path)
            case Content.Val.VoiceMsg(v)      => Set(v.path)
            case Content.Val.VideoMsg(v)      => Set(v.path, v.thumbnailPath)
            case Content.Val.Animation(v)     => Set(v.path, v.thumbnailPath)
            case Content.Val.File(v)          => Set(v.path, v.thumbnailPath)
            case Content.Val.Location(v)      => Set.empty[Option[String]]
            case Content.Val.Poll(v)          => Set.empty[Option[String]]
            case Content.Val.SharedContact(v) => Set(v.vcardPath)
            case Content.Val.Empty            => throw new IllegalArgumentException("Empty content!")
          }).yieldDefined.map(s => new JFile(s))
      })
    }

    override def =~=(that: Message): Boolean = that match {
      case that: Message.Regular =>
        val contentEquals = (this.contentOption, that.contentOption) match {
          case (None, None)               => true
          case (Some(thisC), Some(thatC)) => thisC =~= thatC
          case _                          => false
        }
        contentEquals && (
          this.copy(
            internalId            = Message.NoInternalId,
            forwardFromNameOption = None,
            contentOption         = None
          ) == that.copy(
            internalId            = Message.NoInternalId,
            forwardFromNameOption = None,
            contentOption         = None
          )
        )
      case _ =>
        false
    }
  }

  sealed trait Service extends Message

  object Service {
    sealed trait WithMembers extends Service {
      val members: Seq[String]
    }

    case class PhoneCall(
        internalId: InternalId,
        sourceIdOption: Option[SourceId],
        time: DateTime,
        fromId: Long,
        textOption: Option[RichText],
        durationSecOption: Option[Int],
        discardReasonOption: Option[String]
    ) extends Service {
      override def withInternalId(internalId: Message.InternalId): PhoneCall = copy(internalId = internalId)

      override def withFromId(fromId: Long): PhoneCall = copy(fromId = fromId)

      override def files: Set[JFile] = Set.empty
    }

    case class PinMessage(
        internalId: InternalId,
        sourceIdOption: Option[SourceId],
        time: DateTime,
        fromId: Long,
        textOption: Option[RichText],
        messageId: SourceId
    ) extends Service {
      override def withInternalId(internalId: Message.InternalId): PinMessage = copy(internalId = internalId)

      override def withFromId(fromId: Long): PinMessage = copy(fromId = fromId)

      override def files: Set[JFile] = Set.empty
    }

    /** Note: for Telegram, `from...` is not always meaningful */
    case class ClearHistory(
        internalId: InternalId,
        sourceIdOption: Option[SourceId],
        time: DateTime,
        fromId: Long,
        textOption: Option[RichText]
    ) extends Service {
      override def withInternalId(internalId: Message.InternalId): ClearHistory = copy(internalId = internalId)

      override def withFromId(fromId: Long): ClearHistory = copy(fromId = fromId)

      override def files: Set[JFile] = Set.empty
    }

    object Group {
      case class Create(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText],
          title: String,
          members: Seq[String]
      ) extends WithMembers {
        override def withInternalId(internalId: Message.InternalId): Create = copy(internalId = internalId)

        override def withFromId(fromId: Long): Create = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty

        override val plainSearchableString =
          (plainSearchableMsgString +: title +: members).mkString(" ").trim
      }

      case class EditTitle(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText],
          title: String,
      ) extends Service {
        override def withInternalId(internalId: Message.InternalId): EditTitle = copy(internalId = internalId)

        override def withFromId(fromId: Long): EditTitle = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty
      }

      case class EditPhoto(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText],
          pathOption: Option[JFile],
          widthOption: Option[Int],
          heightOption: Option[Int]
      ) extends Service {
        override def withInternalId(internalId: Message.InternalId): EditPhoto = copy(internalId = internalId)

        override def withFromId(fromId: Long): EditPhoto = copy(fromId = fromId)

        override def files: Set[JFile] = Set(pathOption).yieldDefined

        override def =~=(that: Message) = that match {
          case that: Message.Service.Group.EditPhoto =>
            (this.pathOption =~= that.pathOption) &&
              (this.copy(
                internalId = Message.NoInternalId,
                pathOption = None
              ) == that.copy(
                internalId = Message.NoInternalId,
                pathOption = None
              ))
          case _ =>
            false
        }
      }

      case class InviteMembers(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText],
          members: Seq[String]
      ) extends WithMembers {
        override def withInternalId(internalId: Message.InternalId): InviteMembers = copy(internalId = internalId)

        override def withFromId(fromId: Long): InviteMembers = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty

        override val plainSearchableString =
          (plainSearchableMsgString +: members).mkString(" ").trim
      }

      case class RemoveMembers(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText],
          members: Seq[String]
      ) extends WithMembers {
        override def withInternalId(internalId: Message.InternalId): RemoveMembers = copy(internalId = internalId)

        override def withFromId(fromId: Long): RemoveMembers = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty

        override val plainSearchableString =
          (plainSearchableMsgString +: members).mkString(" ").trim
      }

      case class MigrateFrom(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          titleOption: Option[String],
          textOption: Option[RichText]
      ) extends Service {
        override def withInternalId(internalId: Message.InternalId): MigrateFrom = copy(internalId = internalId)

        override def withFromId(fromId: Long): MigrateFrom = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty

        override val plainSearchableString =
          (plainSearchableMsgString + " " + titleOption.getOrElse("")).trim
      }

      case class MigrateTo(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText]
      ) extends Service {
        override def withInternalId(internalId: Message.InternalId): MigrateTo = copy(internalId = internalId)

        override def withFromId(fromId: Long): MigrateTo = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty
      }

      /** This is different to PhoneCall */
      case class Call(
          internalId: InternalId,
          sourceIdOption: Option[SourceId],
          time: DateTime,
          fromId: Long,
          textOption: Option[RichText],
          members: Seq[String]
      ) extends WithMembers {
        override def withInternalId(internalId: Message.InternalId): Call = copy(internalId = internalId)

        override def withFromId(fromId: Long): Call = copy(fromId = fromId)

        override def files: Set[JFile] = Set.empty

        override val plainSearchableString =
          (plainSearchableMsgString +: members).mkString(" ").trim
      }
    }
  }
}

object Helpers {

  //
  // Rich Text
  //

  implicit class ScalaRichRTE(rte: RichTextElement) {
    def textOption: Option[String] = {
      if (rte.`val`.isEmpty) {
        None
      } else rte.`val`.value match {
        case el: RtePlain         => el.text.toOption
        case el: RteBold          => el.text.toOption
        case el: RteItalic        => el.text.toOption
        case el: RteUnderline     => el.text.toOption
        case el: RteStrikethrough => el.text.toOption
        case el: RteLink          => el.text
        case el: RtePrefmtInline  => el.text.toOption
        case el: RtePrefmtBlock   => el.text.toOption
      }
    }

    def textOrEmptyString: String =
      textOption match {
        case None    => ""
        case Some(s) => s
      }
  }

  //
  // Content
  //

  implicit class ScalaRichContent(c: Content) {
    def pathFileOption: Option[JFile] = {
      require(hasPath, "No path available!")
      c.`val`.value match {
        case c: ContentSticker    => c.pathFileOption
        case c: ContentPhoto      => c.pathFileOption
        case c: ContentVoiceMsg   => c.pathFileOption
        case c: ContentVideoMsg   => c.pathFileOption
        case c: ContentAnimation  => c.pathFileOption
        case c: ContentFile       => c.pathFileOption
        case c                    => None
      }
    }

    def hasPath: Boolean =
      c.`val`.value match {
        case c: ContentSticker   => true
        case c: ContentPhoto     => true
        case c: ContentVoiceMsg  => true
        case c: ContentVideoMsg  => true
        case c: ContentAnimation => true
        case c: ContentFile      => true
        case c                   => false
      }

    def =~=(that: Content): Boolean = {
      (c.`val`, that.`val`) match {
        case (Content.Val.Empty, Content.Val.Empty) => true
        case (Content.Val.Sticker(c1), Content.Val.Sticker(c2)) =>
          c1.pathFileOption =~= c2.pathFileOption &&
            c1.thumbnailPathFileOption =~= c2.thumbnailPathFileOption &&
            c1.copy(path = None, thumbnailPath = None) == c2.copy(path = None, thumbnailPath = None)
        case (Content.Val.Photo(c1), Content.Val.Photo(c2)) =>
          c1.pathFileOption =~= c2.pathFileOption &&
            c1.copy(path = None) == c2.copy(path = None)
        case (Content.Val.VoiceMsg(c1), Content.Val.VoiceMsg(c2)) =>
          c1.pathFileOption =~= c2.pathFileOption &&
            c1.copy(path = None) == c2.copy(path = None)
        case (Content.Val.VideoMsg(c1), Content.Val.VideoMsg(c2)) =>
          c1.pathFileOption =~= c2.pathFileOption &&
            c1.thumbnailPathFileOption =~= c2.thumbnailPathFileOption &&
            c1.copy(path = None, thumbnailPath = None) == c2.copy(path = None, thumbnailPath = None)
        case (Content.Val.Animation(c1), Content.Val.Animation(c2)) =>
          c1.pathFileOption =~= c2.pathFileOption &&
            c1.thumbnailPathFileOption =~= c2.thumbnailPathFileOption &&
            c1.copy(path = None, thumbnailPath = None) == c2.copy(path = None, thumbnailPath = None)
        case (Content.Val.File(c1), Content.Val.File(c2)) =>
          c1.pathFileOption =~= c2.pathFileOption &&
            c1.thumbnailPathFileOption =~= c2.thumbnailPathFileOption &&
            c1.copy(path = None, thumbnailPath = None) == c2.copy(path = None, thumbnailPath = None)
        case (Content.Val.Location(c1), Content.Val.Location(c2)) =>
          c1 == c2
        case (Content.Val.Poll(c1), Content.Val.Poll(c2)) =>
          // We don't really care about poll result
          c1 == c2
        case (Content.Val.SharedContact(c1), Content.Val.SharedContact(c2)) =>
          c1.vcardFileOption =~= c2.vcardFileOption &&
            c1.copy(vcardPath = None) == c2.copy(vcardPath = None)
        case _ => false
      }
    }

    def !=~=(that: Content): Boolean =
      !(this =~= that)
  }

  implicit class ScalaRichSticker(c: ContentSticker) {
    def pathFileOption: Option[JFile] = c.path.map(s => new JFile(s))

    def thumbnailPathFileOption: Option[JFile] = c.thumbnailPath.map(s => new JFile(s))
  }

  implicit class ScalaRichPhoto(c: ContentPhoto) {
    def pathFileOption: Option[JFile] = c.path.map(s => new JFile(s))
  }

  implicit class ScalaRichVoiceMsg(c: ContentVoiceMsg) {
    def pathFileOption: Option[JFile] = c.path.map(s => new JFile(s))
  }

  implicit class ScalaRichVideoMsg(c: ContentVideoMsg) {
    def pathFileOption: Option[JFile] = c.path.map(s => new JFile(s))

    def thumbnailPathFileOption: Option[JFile] = c.thumbnailPath.map(s => new JFile(s))
  }

  implicit class ScalaRichAnimation(c: ContentAnimation) {
    def pathFileOption: Option[JFile] = c.path.map(s => new JFile(s))

    def thumbnailPathFileOption: Option[JFile] = c.thumbnailPath.map(s => new JFile(s))
  }

  implicit class ScalaRichFile(c: ContentFile) {
    def pathFileOption: Option[JFile] = c.path.map(s => new JFile(s))

    def thumbnailPathFileOption: Option[JFile] = c.thumbnailPath.map(s => new JFile(s))
  }

  implicit class ScalaRichLocation(c: ContentLocation) {
    def lat: BigDecimal = BigDecimal(c.latStr)

    def lon: BigDecimal = BigDecimal(c.lonStr)
  }

  implicit class ScalaRichSharedContact(c: ContentSharedContact) {
    def vcardFileOption: Option[JFile] = c.vcardPath.map(s => new JFile(s))

    // TODO: Merge with PersonInfo!
    lazy val prettyNameOption: Option[String] = {
      val parts = Seq(Some(c.firstName), c.lastName).yieldDefined
      if (parts.isEmpty) None else Some(parts.mkString(" ").trim)
    }

    lazy val prettyName: String =
      prettyNameOption getOrElse ChatHistoryDao.Unnamed
  }
}
