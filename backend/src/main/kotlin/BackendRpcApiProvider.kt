package com.example

import com.example.backend.rpc.VisualiserRpcApiImpl
import com.example.rpc.VisualiserRpcApi
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class BackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<VisualiserRpcApi>()) {
            VisualiserRpcApiImpl()
        }
    }
}
