package org.cakesolutions.akkapatterns.main

import akka.actor.{Props, ActorSystem}
import com.aphelia.amqp.{ChannelOwner, ConnectionOwner, RpcClient}
import com.aphelia.amqp.Amqp._
import java.io.ByteArrayOutputStream
import akka.pattern.ask
import akka.util.Timeout
import com.rabbitmq.client.{DefaultConsumer, Channel, Envelope, ConnectionFactory}
import com.rabbitmq.client.AMQP.BasicProperties
import com.aphelia.amqp.Amqp.ReturnedMessage
import com.aphelia.amqp.Amqp.Publish
import com.aphelia.amqp.Amqp.ChannelParameters
import com.aphelia.amqp.RpcClient.Response
import scala.Some
import util.Success
import com.aphelia.amqp.RpcClient.Request
import com.aphelia.amqp.Amqp.QueueParameters
import com.aphelia.amqp.Amqp.Delivery
import akka.actor.SupervisorStrategy.Stop

/**
 * @author janmachacek
 */
object ClientDemo {

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val timeout = Timeout(100000L)

  def main(args: Array[String]) {
    val actorSystem = ActorSystem("AkkaPatterns")

    // RabbitMQ connection factory
    val connectionFactory = new ConnectionFactory()
    connectionFactory.setHost("localhost")
    connectionFactory.setVirtualHost("/")
    //val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    //val queue = QueueParameters(name = UUID.randomUUID().toString, passive = true, exclusive = true, autodelete = true)

    // create a "connection owner" actor, which will try and reconnect automatically if the connection is lost
    val connection = actorSystem.actorOf(Props(new ConnectionOwner(connectionFactory)))

    // make a client actor
    // val client = ConnectionOwner.createChildActor(connection, Props(new RpcClient()))
    val streamingClient = ConnectionOwner.createChildActor(connection, Props(new RpcStreamingClient()))

    Thread.sleep(1000)

    streamingClient ! Request(Publish("amq.direct", "sound.key", "/Users/janmachacek/Desktop/x.jpg".getBytes) :: Nil)
    Thread.sleep(100000)
    streamingClient ! Stop

    // publish a request
    val os = new ByteArrayOutputStream()
    // header
    os.write(0xca)
    os.write(0xac)
    os.write(0x00)
    os.write(0x10)

    // len 1
    os.write(0x00)
    os.write(0x00)
    os.write(0x00)
    os.write(0x00)

    // len 2
    os.write(0x00)
    os.write(0x00)
    os.write(0x00)
    os.write(0x00)

    /*val publish = Publish("amq.direct", "demo.key", os.toByteArray)
    client ? Request(publish :: Nil) onComplete {
      case Success(response: Response) => println(response)
      case x => println("Bantha poodoo!" + x)
    }
    */
  }

  class RpcStreamingClient(channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {
    var queue: String = ""
    var consumer: Option[DefaultConsumer] = None

    override def onChannel(channel: Channel) {
      // create a private, exclusive reply queue; its name will be randomly generated by the broker
      queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
      consumer = Some(new DefaultConsumer(channel) {
        override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
          self ! Delivery(consumerTag, envelope, properties, body)
        }
      })
      channel.basicConsume(queue, false, consumer.get)
    }

    when(ChannelOwner.Connected) {
      case Event(p: Publish, ChannelOwner.Connected(channel)) => {
        val props = p.properties.getOrElse(new BasicProperties()).builder.replyTo(queue).build()
        channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, props, p.body)
        stay()
      }
      case Event(Stop, ChannelOwner.Connected(channel)) =>
        channel.close()
        stop()
      case Event(Request(publish, numberOfResponses), ChannelOwner.Connected(channel)) => {
        publish.foreach(p => {
          val props = p.properties.getOrElse(new BasicProperties()).builder.replyTo(queue).build()
          channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, props, p.body)
        })
        stay()
      }
      case Event(delivery@Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
        channel.basicAck(envelope.getDeliveryTag, false)
        println("Received message")
        stay()
      }
      case Event(msg@ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body), ChannelOwner.Connected(channel)) => {
        stay()
      }
    }
  }
}
