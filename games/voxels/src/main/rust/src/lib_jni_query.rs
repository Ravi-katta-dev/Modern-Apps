/// JNI bridge for `VoxelsNative#getRecipesJson`: returns the crafting
/// recipe catalog as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getRecipesJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_recipes_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getInventoryJson`: returns the player
/// inventory as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getInventoryJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let json = engine::get_inventory_json();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getDebugJson`: returns the debug overlay
/// state as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getDebugJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let json = engine::get_debug_json();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getStatsJson`: returns player stats as a
/// JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getStatsJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let json = engine::get_stats_json();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getHealthJson`: returns player health state
/// as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getHealthJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_health_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getSmeltingJson`: returns furnace progress
/// state as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getSmeltingJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_smelting_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getSmeltJson`: returns the furnace recipe
/// list as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getSmeltJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_smelt_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getAmbienceJson`: returns ambience state as a
/// JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getAmbienceJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_ambience_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getContainerJson`: returns the open
/// container contents as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getContainerJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_container_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getBlessingsJson`: returns attuned blessings
/// as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getBlessingsJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_blessings_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getBlessingCatalogJson`: returns the blessing
/// catalog as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getBlessingCatalogJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_blessing_catalog_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI bridge for `VoxelsNative#getCutsJson`: returns the stonecutter recipe
/// list as a JSON string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getCutsJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_cuts_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
