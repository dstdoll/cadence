//! JNI surface over `auth-core`: real Apple ID sign-in (SRP + HSA2 code) for Cadence.
//! Names are the JNI-mangled forms of `app.cadence.backend.real.NativeAuth`.
//!
//! The working PC desktop login tool fetches the MusicKit web-player JWT **before**
//! touching SRP, and saves the authenticated session before the token exchange.
//! This bridge does the same: without the pre-auth JWT the exchange fails after
//! the code and the app reports "can't reach the server".

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use tokio::runtime::Runtime;

use auth_core::idmsa::{AuthSessionStore, IdmsaClient};

static RT: OnceLock<Runtime> = OnceLock::new();
static CLIENT: Mutex<Option<IdmsaClient>> = Mutex::new(None);
/// Trusted phone chosen for SMS delivery; also selects the submit path.
static SMS_PHONE: Mutex<Option<u32>> = Mutex::new(None);
/// Web-player JWT fetched before SRP, reused for the MusicKit token exchange.
static JWT: Mutex<Option<String>> = Mutex::new(None);
/// App files directory; the authenticated session is persisted there (0600).
static STORAGE: Mutex<Option<PathBuf>> = Mutex::new(None);

fn runtime() -> &'static Runtime {
    RT.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

fn err_json(kind: &str, message: &str) -> String {
    serde_json::json!({ "status": "error", "kind": kind, "message": message }).to_string()
}

fn ok_json(session: &auth_core::Session) -> String {
    serde_json::json!({
        "status": "ok",
        "session": {
            "mediaUserToken": session.media_user_token,
            "storefront": session.storefront,
            "dsid": session.dsid,
        }
    })
    .to_string()
}

fn session_store() -> Option<AuthSessionStore> {
    STORAGE
        .lock()
        .unwrap()
        .clone()
        .map(|dir| AuthSessionStore::new(dir))
}

fn save_auth_session(client: &IdmsaClient) {
    let Some(store) = session_store() else { return };
    let saved = client.export_auth_session();
    if let Err(err) = store.save(&saved) {
        // Never fail the login because persistence failed; log through the JNI app.
        eprintln!("[auth] save session: {err}");
    }
}

fn clear_auth_session() {
    if let Some(store) = session_store() {
        let _ = store.clear();
    }
}

async fn fetch_jwt(client: &IdmsaClient) -> Result<String, String> {
    let jwt = client
        .fetch_web_player_token()
        .await
        .map_err(|err| format!("{err}"))?;
    *JWT.lock().unwrap() = Some(jwt.clone());
    Ok(jwt)
}

/// Exchanges the authenticated session for a media-user-token. Retries a few
/// times: the exchange is the step that used to surface as "can't reach the server".
async fn finish_session(client: &mut IdmsaClient) -> Result<auth_core::Session, String> {
    let jwt = match JWT.lock().unwrap().clone() {
        Some(jwt) if !jwt.is_empty() => jwt,
        _ => fetch_jwt(client).await?,
    };
    let mut last = String::new();
    for attempt in 0..3u64 {
        match client.finish_login(&jwt).await {
            Ok(session) => return Ok(session),
            Err(err) => {
                last = format!("{err}");
                if attempt < 2 {
                    tokio::time::sleep(Duration::from_millis(700 * (attempt + 1))).await;
                }
            }
        }
    }
    Err(last)
}

fn out(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Trusted phones for the account (id + numberWithDialCode).
async fn phones(client: &mut IdmsaClient) -> Vec<(u32, String)> {
    match client.account_info().await {
        Ok(info) => info
            .trusted_phones
            .iter()
            .map(|p| (p.id, p.number.clone()))
            .collect(),
        Err(_) => Vec::new(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_real_NativeAuth_cadence_1auth_1start(
    mut env: JNIEnv,
    _class: JClass,
    email: JString,
    password: JString,
    storage_dir: JString,
) -> jstring {
    let email: String = env.get_string(&email).map(|s| s.into()).unwrap_or_default();
    let password: String = env.get_string(&password).map(|s| s.into()).unwrap_or_default();
    let storage: String = env.get_string(&storage_dir).map(|s| s.into()).unwrap_or_default();
    if storage.is_empty() {
        return out(&mut env, err_json("storage", "no app files directory"));
    }
    *STORAGE.lock().unwrap() = Some(PathBuf::from(&storage));
    *CLIENT.lock().unwrap() = None;
    *SMS_PHONE.lock().unwrap() = None;
    *JWT.lock().unwrap() = None;

    let result = runtime().block_on(async move {
        let mut client = match IdmsaClient::new() {
            Ok(c) => c,
            Err(e) => return err_json("client", &format!("{e}")),
        };
        // Must happen before SRP: this sets the MusicKit widget context/cookies
        // used by the later token exchange.
        if let Err(e) = fetch_jwt(&client).await {
            return err_json("web_player_token", &e);
        }
        match client.authenticate(&email, &password, None).await {
            Ok(()) => {
                save_auth_session(&client);
                match finish_session(&mut client).await {
                    Ok(session) => {
                        *CLIENT.lock().unwrap() = None;
                        ok_json(&session)
                    }
                    Err(e) => err_json("music_token", &e),
                }
            }
            Err(auth_core::AuthError::TwoFactorRequired { .. }) => {
                let phones = phones(&mut client).await;
                let chosen = phones.first().map(|(id, _)| *id);
                if let Some(id) = chosen {
                    let _ = client.request_sms(id).await;
                }
                *SMS_PHONE.lock().unwrap() = chosen;
                *CLIENT.lock().unwrap() = Some(client);
                serde_json::json!({
                    "status": "code_required",
                    "delivery": if chosen.is_some() { "sms" } else { "device" },
                    "phones": phones.iter().map(|(id, n)| serde_json::json!({"id": id, "number": n})).collect::<Vec<_>>(),
                })
                .to_string()
            }
            Err(e) => err_json("auth", &format!("{e}")),
        }
    });
    out(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_real_NativeAuth_cadence_1auth_1submit(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
) -> jstring {
    let code: String = env.get_string(&code).map(|s| s.into()).unwrap_or_default();
    let result = runtime().block_on(async move {
        let mut guard = CLIENT.lock().unwrap();
        let Some(client) = guard.as_mut() else {
            return err_json("state", "no challenge in progress");
        };
        let phone = *SMS_PHONE.lock().unwrap();
        let submitted = match phone {
            Some(id) => client.submit_sms_code(&code, id).await,
            None => client.submit_code(&code).await,
        };
        match submitted {
            Ok(()) => {
                // Persist before the exchange so a failed exchange is retryable
                // with `resume` instead of a new (already consumed) 2FA code.
                save_auth_session(client);
                match finish_session(client).await {
                    Ok(session) => {
                        *guard = None;
                        ok_json(&session)
                    }
                    Err(e) => err_json("music_token", &e),
                }
            }
            Err(auth_core::AuthError::InvalidCode) => {
                err_json("code", "the verification code was not accepted")
            }
            Err(e) => err_json("code", &format!("{e}")),
        }
    });
    out(&mut env, result)
}

/// Retries the MusicKit token exchange from the in-memory or saved session.
#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_real_NativeAuth_cadence_1auth_1resume(
    mut env: JNIEnv,
    _class: JClass,
    storage_dir: JString,
) -> jstring {
    let storage: String = env.get_string(&storage_dir).map(|s| s.into()).unwrap_or_default();
    if !storage.is_empty() {
        *STORAGE.lock().unwrap() = Some(PathBuf::from(&storage));
    }
    let result = runtime().block_on(async {
        let mut guard = CLIENT.lock().unwrap();
        if guard.is_none() {
            let Some(store) = session_store() else {
                return err_json("state", "no saved session");
            };
            let saved = match store.load() {
                Ok(Some(saved)) => saved,
                Ok(None) => return err_json("state", "no saved session"),
                Err(e) => return err_json("state", &format!("{e}")),
            };
            let mut client = match IdmsaClient::new() {
                Ok(c) => c,
                Err(e) => return err_json("client", &format!("{e}")),
            };
            if let Err(e) = client.import_auth_session(&saved) {
                return err_json("state", &format!("{e}"));
            }
            if let Err(e) = fetch_jwt(&client).await {
                return err_json("web_player_token", &e);
            }
            *guard = Some(client);
        }
        let client = guard.as_mut().expect("client set above");
        match finish_session(client).await {
            Ok(session) => {
                *guard = None;
                ok_json(&session)
            }
            Err(e) => err_json("music_token", &e),
        }
    });
    out(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_real_NativeAuth_cadence_1auth_1reset(
    mut env: JNIEnv,
    _class: JClass,
    storage_dir: JString,
) {
    let storage: String = env.get_string(&storage_dir).map(|s| s.into()).unwrap_or_default();
    if !storage.is_empty() {
        *STORAGE.lock().unwrap() = Some(PathBuf::from(&storage));
    }
    *CLIENT.lock().unwrap() = None;
    *SMS_PHONE.lock().unwrap() = None;
    *JWT.lock().unwrap() = None;
    clear_auth_session();
}

#[no_mangle]
pub extern "system" fn Java_app_cadence_backend_real_NativeAuth_cadence_1auth_1free(
    _env: JNIEnv,
    _class: JClass,
    _ptr: jlong,
) {
    // strings are returned as Java strings, so there is nothing to free here
}
