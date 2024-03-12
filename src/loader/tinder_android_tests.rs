#![allow(unused_imports)]

use std::fmt::format;
use std::fs;
use std::path::PathBuf;
use chrono::prelude::*;
use lazy_static::lazy_static;
use pretty_assertions::{assert_eq, assert_ne};

use crate::{NoChooser, User};
use crate::dao::ChatHistoryDao;
use crate::entity_utils::*;
use crate::protobuf::history::content::SealedValueOptional::*;
use crate::protobuf::history::message::*;
use crate::protobuf::history::message_service::SealedValueOptional::*;

use super::*;

const RESOURCE_DIR: &str = "tinder-android";

lazy_static! {
    static ref LOADER: TinderAndroidDataLoader<MockHttpClient> = TinderAndroidDataLoader {
        http_client: &HTTP_CLIENT
    };
}

//
// Tests
//

#[test]
fn loading_2023_11() -> EmptyRes {
    let (res, db_dir) = test_android::create_databases(RESOURCE_DIR, "2023-11", ".db", DB_FILENAME);
    let _media_dir = TmpDir::new_at(db_dir.path.parent().unwrap().join(MEDIA_DIR));

    LOADER.looks_about_right(&res)?;
    let dao = LOADER.load(&res, &NoChooser)?;

    let ds_uuid = &dao.ds_uuid();
    let myself = dao.myself_single_ds();
    assert_eq!(myself, expected_myself(ds_uuid));

    let member = User {
        ds_uuid: ds_uuid.clone(),
        id: 780327027359649707_i64,
        first_name_option: Some("Abcde".to_owned()),
        last_name_option: None,
        username_option: None,
        phone_number_option: None,
    };

    assert_eq!(dao.users_single_ds(), vec![myself.clone(), member.clone()]);

    assert_eq!(dao.cwms_single_ds().len(), 1);

    {
        let cwm = dao.cwms_single_ds().remove(0);
        let chat = cwm.chat;
        assert_eq!(chat, Chat {
            ds_uuid: ds_uuid.clone(),
            id: member.id,
            name_option: Some("Abcde".to_owned()),
            source_type: SourceType::TinderDb as i32,
            tpe: ChatType::Personal as i32,
            img_path_option: None,
            member_ids: vec![myself.id, member.id],
            msg_count: 2,
            main_chat_id: None,
        });

        let msgs = dao.first_messages(&chat, 99999)?;
        assert_eq!(msgs.len() as i32, chat.msg_count);

        assert_eq!(msgs[0], Message {
            internal_id: 0,
            source_id_option: Some(869569426176655274),
            timestamp: 1699812983,
            from_id: myself.id,
            text: vec![RichText::make_plain("Sending you a text!".to_owned())],
            searchable_string: "Sending you a text!".to_owned(),
            typed: Some(MESSAGE_REGULAR_NO_CONTENT.clone()),
        });
        assert_eq!(msgs[1], Message {
            internal_id: 1,
            source_id_option: Some(5405907581016140653),
            timestamp: 1699813000,
            from_id: member.id,
            text: vec![],
            searchable_string: "".to_owned(),
            typed: Some(message_regular! {
                edit_timestamp_option: None,
                is_deleted: false,
                forward_from_name_option: None,
                reply_to_message_id_option: None,
                content_option: Some(Content {
                    sealed_value_optional: Some(Sticker(ContentSticker {
                        path_option: Some(format!("{RELATIVE_MEDIA_DIR}/848013095925873688.gif")),
                        width: 542,
                        height: 558,
                        thumbnail_path_option: None,
                        emoji_option: None,
                    }))
                }),
            }),
        });
    }

    assert_eq!(HTTP_CLIENT.calls_copy(),
               vec!["https://media.tenor.com/mYFQztB4EHoAAAAC/house-hugh-laurie.gif?width=271&height=279"]);

    Ok(())
}

//
// Helpers
//

fn expected_myself(ds_uuid: &PbUuid) -> User {
    User {
        ds_uuid: ds_uuid.clone(),
        id: 1_i64,
        first_name_option: Some("Me".to_owned()),
        last_name_option: None,
        username_option: None,
        phone_number_option: None,
    }
}
