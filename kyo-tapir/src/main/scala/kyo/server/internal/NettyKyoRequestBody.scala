package sttp.tapir.server.netty.internal

import io.netty.handler.codec.http.HttpContent
import kyo.Fibers
import kyo.internal.KyoSttpMonad
import org.reactivestreams.Publisher
import sttp.capabilities
import sttp.monad.MonadError
import sttp.tapir.TapirFile
import sttp.tapir.capabilities.NoStreams
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.netty.internal.reactivestreams.SimpleSubscriber

private[netty] class NettyKyoRequestBody(val createFile: ServerRequest => KyoSttpMonad.M[TapirFile])
    extends NettyRequestBody[KyoSttpMonad.M, NoStreams]:

    override val streams: capabilities.Streams[NoStreams]   = NoStreams
    implicit override val monad: MonadError[KyoSttpMonad.M] = KyoSttpMonad.instance

    override def publisherToBytes(
        publisher: Publisher[HttpContent],
        maxBytes: Option[Long]
    ): KyoSttpMonad.M[Array[Byte]] =
        Fibers.fromFuture(SimpleSubscriber.processAll(publisher, maxBytes))

    override def writeToFile(
        serverRequest: ServerRequest,
        file: TapirFile,
        maxBytes: Option[Long]
    ): KyoSttpMonad.M[Unit] =
        throw new UnsupportedOperationException()

    override def toStream(
        serverRequest: ServerRequest,
        maxBytes: Option[Long]
    ): streams.BinaryStream =
        throw new UnsupportedOperationException()
end NettyKyoRequestBody
