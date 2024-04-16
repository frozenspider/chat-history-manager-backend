use std::fmt::Debug;
use std::net::SocketAddr;
use std::sync::{Mutex, MutexGuard, RwLock, RwLockReadGuard, RwLockWriteGuard};
use std::sync::Arc;

use indexmap::IndexMap;
use tonic::{Code, Request, Response, Status, transport::Server};

use crate::dao::ChatHistoryDao;
use crate::loader::Loader;
use crate::prelude::*;
use crate::protobuf::history::history_dao_service_server::HistoryDaoServiceServer;
use crate::protobuf::history::history_loader_service_server::HistoryLoaderServiceServer;
use crate::protobuf::history::merge_service_server::MergeServiceServer;

use super::client::{self, MyselfChooser};

mod history_loader_service;
mod history_dao_service;
mod merge_service;

pub(crate) const FILE_DESCRIPTOR_SET: &[u8] =
    tonic::include_file_descriptor_set!("grpc_reflection_descriptor");

type StatusResult<T> = StdResult<T, Status>;
type TonicResult<T> = StatusResult<Response<T>>;

// Abosulte path to data source
type DaoKey = String;
type DaoMutex = Mutex<Box<dyn ChatHistoryDao>>;

// Should be used wrapped as Arc<Self>
pub struct ChatHistoryManagerServer {
    loader: Loader,
    myself_chooser: Box<dyn MyselfChooser>,
    loaded_daos: RwLock<IndexMap<DaoKey, DaoMutex>>,
}

impl ChatHistoryManagerServer {
    pub fn new_wrapped(loader: Loader, myself_chooser: Box<dyn MyselfChooser>) -> Arc<Self> {
        Arc::new(ChatHistoryManagerServer {
            loader,
            myself_chooser,
            loaded_daos: RwLock::new(IndexMap::new()),
        })
    }
}

trait ChatHistoryManagerServerTrait {
    fn process_request<Q, P, L>(&self, req: &Request<Q>, logic: L) -> TonicResult<P>
        where Q: Debug,
              P: Debug,
              L: FnMut(&Q) -> Result<P>;

    fn process_request_with_dao<Q, P, L>(&self, req: &Request<Q>, key: &DaoKey, logic: L) -> TonicResult<P>
        where Q: Debug,
              P: Debug,
              L: FnMut(&Q, &mut dyn ChatHistoryDao) -> Result<P>;
}

impl ChatHistoryManagerServerTrait for ChatHistoryManagerServer {
    fn process_request<Q, P, L>(&self, req: &Request<Q>, mut logic: L) -> TonicResult<P>
        where Q: Debug,
              P: Debug,
              L: FnMut(&Q) -> Result<P> {
        log::debug!(">>> Request:  {}", truncate_to(format!("{:?}", req.get_ref()), 150));
        let response_result = logic(req.get_ref())
            .map(Response::new);
        log::debug!("<<< Response: {}", truncate_to(format!("{:?}", response_result), 150));
        response_result.map_err(|err| {
            eprintln!("Request failed! Error was:\n{:?}", err);
            Status::new(Code::Internal, error_to_string(&err))
        })
    }

    fn process_request_with_dao<Q, P, L>(&self, req: &Request<Q>, key: &DaoKey, mut logic: L) -> TonicResult<P>
        where Q: Debug,
              P: Debug,
              L: FnMut(&Q, &mut dyn ChatHistoryDao) -> Result<P> {
        let loaded_daos = read_or_status(&self.loaded_daos)?;
        let dao = loaded_daos.get(key)
            .ok_or_else(|| Status::new(Code::FailedPrecondition,
                                       format!("Database with key {key} is not loaded!")))?;
        let mut dao = lock_or_status(dao)?;
        let dao = dao.as_mut();

        self.process_request(req, |req| logic(req, dao))
    }
}

// https://betterprogramming.pub/building-a-grpc-server-with-rust-be2c52f0860e
pub async fn start_server(port: u16, loader: Loader) -> EmptyRes {
    let addr = format!("127.0.0.1:{port}").parse::<SocketAddr>().unwrap();

    let remote_port = port + 1;

    let myself_chooser = client::create_myself_chooser(remote_port).await?;
    let chm_server = ChatHistoryManagerServer::new_wrapped(loader, myself_chooser);

    log::info!("Server listening on {}", addr);

    let reflection_service = tonic_reflection::server::Builder::configure()
        .register_encoded_file_descriptor_set(FILE_DESCRIPTOR_SET)
        .build()
        .unwrap();

    // We need to wrap services in tonic_web::enable to enable Cross-Origin Resource Sharing (CORS),
    // i.e. setting Access-Control-Allow-* response headers.
    // See https://github.com/hyperium/tonic/pull/1326
    Server::builder()
        .accept_http1(true)
        .add_service(tonic_web::enable(HistoryLoaderServiceServer::new(chm_server.clone())))
        .add_service(tonic_web::enable(HistoryDaoServiceServer::new(chm_server.clone())))
        .add_service(tonic_web::enable(MergeServiceServer::new(chm_server)))
        .add_service(reflection_service)
        .serve(addr)
        .await?;

    Ok(())
}

fn lock_or_status<T>(target: &Mutex<T>) -> StatusResult<MutexGuard<'_, T>> {
    target.lock().map_err(|_| Status::new(Code::Internal, "Mutex is poisoned!"))
}

fn read_or_status<T>(target: &RwLock<T>) -> StatusResult<RwLockReadGuard<'_, T>> {
    target.read().map_err(|_| Status::new(Code::Internal, "RwLock is poisoned!"))
}

fn write_or_status<T>(target: &RwLock<T>) -> StatusResult<RwLockWriteGuard<'_, T>> {
    target.write().map_err(|_| Status::new(Code::Internal, "RwLock is poisoned!"))
}
