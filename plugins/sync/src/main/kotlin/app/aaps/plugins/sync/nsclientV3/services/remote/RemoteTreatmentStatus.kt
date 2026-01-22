package app.aaps.plugins.sync.nsclientV3.services.remote

enum class RemoteTreatmentStatus {
    CREATING,
    WAITING_FOR_VERIFY,
    VERIFYING,
    VERIFY_SUCCESS,
    VERIFY_FAILED,
    EXECUTING,
    EXECUTE_SUCCESS,
    EXECUTE_FAILED,
}