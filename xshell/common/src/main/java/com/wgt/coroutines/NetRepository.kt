package com.wgt.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class NetRepository {
    suspend fun getImage(imageId: String) {
        CoroutineScope(Dispatchers.IO).async {
            delay(1000)
            10
        }

    }

}