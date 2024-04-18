use std::future::Future;
use tokio::runtime::Handle;
use tonic::transport::{Channel, Endpoint};

use crate::prelude::*;
use crate::prelude::history_dao_service_client::HistoryDaoServiceClient;
use crate::prelude::history_loader_service_client::HistoryLoaderServiceClient;

mod myself_chooser;

pub trait MyselfChooser: Send + Sync {
    fn choose_myself(&self, users: &[User]) -> Result<usize>;
}

pub async fn create_myself_chooser(remote_port: u16) -> Result<Box<dyn MyselfChooser>> {
    let runtime_handle = Handle::current();
    let lazy_channel = Endpoint::new(format!("http://localhost:{remote_port}"))?.connect_lazy();
    Ok(Box::new(myself_chooser::MyselfChooserImpl { runtime_handle, channel: lazy_channel }))
}

#[derive(Debug, Clone)]
pub struct ChatHistoryManagerGrpcClients {
    loader: HistoryLoaderServiceClient<Channel>,
    dao: HistoryDaoServiceClient<Channel>,
}

impl ChatHistoryManagerGrpcClients {
    pub async fn grpc<'a, F, T>(
        &'a mut self,
        cb: impl FnOnce(&'a mut HistoryLoaderServiceClient<Channel>, &'a mut HistoryDaoServiceClient<Channel>) -> F + 'a,
    ) -> Result<T>
        where F: Future<Output=StdResult<tonic::Response<T>, tonic::Status>>
    {
        match cb(&mut self.loader, &mut self.dao).await {
            Ok(response) => Ok(response.into_inner()),
            Err(status) => Err(anyhow!("{}", status.message()))
        }
    }
}

pub async fn create_clients(remote_port: u16) -> Result<ChatHistoryManagerGrpcClients> {
    let uri = format!("http://localhost:{remote_port}");
    log::info!("Connecting to clients at URI {uri}");
    let channel = Endpoint::new(uri)?.connect_lazy();
    let loader = HistoryLoaderServiceClient::new(channel.clone());
    let dao = HistoryDaoServiceClient::new(channel);
    Ok(ChatHistoryManagerGrpcClients { loader, dao })
}

#[derive(Clone, Copy)]
pub struct NoChooser;

impl MyselfChooser for NoChooser {
    fn choose_myself(&self, _pretty_names: &[User]) -> Result<usize> {
        err!("No way to choose myself!")
    }
}

pub async fn debug_request_myself(port: u16) -> Result<usize> {
    let conn_port = port + 1;
    let chooser = create_myself_chooser(conn_port).await?;

    let ds_uuid = PbUuid { value: "00000000-0000-0000-0000-000000000000".to_owned() };
    let chosen = chooser.choose_myself(&[
        User {
            ds_uuid: ds_uuid.clone(),
            id: 100,
            first_name_option: Some("User 100 FN".to_owned()),
            last_name_option: None,
            username_option: None,
            phone_number_option: None,
        },
        User {
            ds_uuid,
            id: 200,
            first_name_option: None,
            last_name_option: Some("User 200 LN".to_owned()),
            username_option: None,
            phone_number_option: None,
        },
    ])?;
    Ok(chosen)
}
