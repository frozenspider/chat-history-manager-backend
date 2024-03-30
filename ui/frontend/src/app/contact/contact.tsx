'use client'

import React from "react";

import {
  AssertDefined,
  AssertUnreachable,
  GetChatPrettyName,
  GetNonDefaultOrNull,
  GetUserPrettyName,
  NameColorStyleFromNumber
} from "@/app/utils";

import { Chat, ChatType, Message, User } from "@/protobuf/core/protobuf/entities";
import { ChatWithDetailsPB } from "@/protobuf/backend/protobuf/services";

export default function Contact(args: {
  cwd: ChatWithDetailsPB,
  users: Map<bigint, User>,
  myselfId: bigint
}): React.JSX.Element {
  // FIXME: On hover, the dropdown menu should be displayed
  // <div
  //   className="absolute right-0 top-0 hidden group-hover:block bg-white shadow-lg rounded-md mt-2 mr-2 z-10">
  //   <ul className="divide-y divide-gray-200 dark:divide-gray-700">
  //     <li className="p-2 cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-800">View Contact Details</li>
  //   </ul>
  // </div>
  let chat = AssertDefined(args.cwd.chat);
  let color = NameColorStyleFromNumber(chat.id)

  return (
    <li className="p-2 cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-800 group">
      <div className="flex items-center space-x-3">
        <Avatar chat={chat}/>
        <div>
          <span className={"font-semibold " + color}>{GetChatPrettyName(chat)}</span>
          <SimpleMessage chat={chat}
                         msg={GetNonDefaultOrNull(args.cwd.lastMsgOption)}
                         users={args.users}
                         myselfId={args.myselfId}/>
        </div>
      </div>
    </li>
  )
}

function Avatar(args: { chat: Chat }) {
  // TODO: Avatar
  return (
    <img
      alt="User Avatar"
      className="rounded-full"
      height="50"
      src="/placeholder.svg"
      style={{
        aspectRatio: "50/50",
        objectFit: "cover",
      }}
      width="50"
    />
  )
}

function SimpleMessage(args: {
  chat: Chat,
  msg: Message | null,
  users: Map<bigint, User>,
  myselfId: bigint
}) {
  let namePrefix = <></>;
  let text: string = "No messages yet"
  if (args.msg) {
    text = GetMessageSimpleText(args.msg)
    if (args.msg.fromId == args.myselfId) {
      namePrefix = <span>You: </span>
    } else if (args.chat.tpe == ChatType.PRIVATE_GROUP) {
      let user = GetNonDefaultOrNull(args.users.get(args.msg.fromId));
      if (user) {
        namePrefix = <span>{GetUserPrettyName(user) + ": "}</span>
      }
    }
  }
  return (
    <p className="text-sm text-gray-500 line-clamp-2">{namePrefix}{text}</p>
  )
}

function GetMessageSimpleText(msg: Message): string {
  if (msg.typed?.$case === 'regular') {
    let regular = msg.typed.regular
    if (regular.isDeleted)
      return "(message deleted)"

    let regularSvo = regular.contentOption?.sealedValueOptional;
    if (!regularSvo?.$case)
      return msg.searchableString

    switch (regularSvo.$case) {
      case "sticker":
        return regularSvo.sticker.emojiOption ? regularSvo.sticker.emojiOption + " (sticker)" : "(sticker)"
      case "photo":
        return "(photo)"
      case "voiceMsg":
        return "(voice message)"
      case "audio":
        return "(audio)"
      case "videoMsg":
        return "(video message)"
      case "video":
        return "(video)"
      case "file":
        return "(file)"
      case "location":
        return "(location)"
      case "poll":
        return "(poll)"
      case "sharedContact":
        return "(shared contact)"
      default:
        AssertUnreachable(regularSvo)
    }
  } else if (msg.typed?.$case === 'service') {
    let serviceSvo = msg.typed.service.sealedValueOptional
    switch (serviceSvo?.$case) {
      case 'phoneCall':
        return "(call)"
      case 'suggestProfilePhoto':
        return "(suggested photo)"
      case 'pinMessage':
        return "(message pinned)"
      case 'clearHistory':
        return "(history cleared)"
      case 'blockUser':
        return "(user " + (serviceSvo.blockUser.isBlocked ? "" : "un") + "blocked)"
      case 'statusTextChanged':
        return "(status) " + msg.searchableString
      case 'notice':
        return "(notice) " + msg.searchableString
      case 'groupCreate':
        return "(group created)"
      case 'groupEditTitle':
        return "(title changed)"
      case 'groupEditPhoto':
        return "(photo changed)"
      case 'groupDeletePhoto':
        return "(photo deleted)"
      case 'groupInviteMembers':
        return "(invited members)"
      case 'groupRemoveMembers':
        return "(removed members)"
      case 'groupMigrateFrom':
        return "(migrated from group)"
      case 'groupMigrateTo':
        return "(migrated to group)"
      case undefined:
        throw new Error("Undefined service message type: " + JSON.stringify(serviceSvo))
      default:
        AssertUnreachable(serviceSvo)
    }
  } else {
    throw new Error("Unexpected message type: " + JSON.stringify(msg))
  }
}

