package io.devicefarmer.minicap.utils


class LoggerFactory {
    companion object {
        private var logger: Logger? = null

        fun getLogger(): Logger {
            if (logger == null) {
                logger = Logger()
            }
            return logger!!
        }
    }
}

