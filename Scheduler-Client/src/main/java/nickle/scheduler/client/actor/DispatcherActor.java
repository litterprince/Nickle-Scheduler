package nickle.scheduler.client.actor;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.dispatch.OnComplete;
import akka.pattern.Patterns;
import akka.routing.RoundRobinPool;
import akka.util.Timeout;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import nickle.scheduler.client.event.ActiveJobEvent;
import nickle.scheduler.common.event.ExecuteJobEvent;
import nickle.scheduler.common.event.RegisterEvent;
import org.apache.commons.lang3.ObjectUtils;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static nickle.scheduler.client.actor.HeartBeatActor.HEART_BEAT;
import static nickle.scheduler.client.constant.Constants.ACTIVE_JOB_EVENT;
import static nickle.scheduler.common.Constant.*;

/**
 * 执行器分配器actor，接受任务调度的消息并启动任务
 *
 * @author nickle
 */
@Slf4j
public class DispatcherActor extends AbstractActor {
    private RegisterEvent registerEvent;

    @Data
    public static class JobDetail {
        private List<RegisterEvent.JobData> jobDataList;
        private List<RegisterEvent.TriggerData> triggerDataList;
    }

    private List<String> masterList;
    private boolean registerSuccess;
    private ActorRef executorRouter;

    public static Props props() {
        return Props.create(DispatcherActor.class);
    }

    @Override
    public void preStart() {
        log.info("执行器分配器启动");
        executorRouter = getContext().actorOf(Props.create(ExecutorActor.class).withRouter(new RoundRobinPool(3)),
                "ExecutorRouter");
        log.info("执行器分配器启动成功");
    }

    /**
     * 将分配器注册到远程actor上
     */
    public void init(List<RegisterEvent.JobData> jobDataList, List<RegisterEvent.TriggerData> triggerDataList) {
        Config config = getContext().getSystem().settings().config();
        masterList = config.getStringList("remote.actor.path");
        if (!ObjectUtils.isEmpty(masterList)) {
            retryRegister(jobDataList, triggerDataList);
        }
    }

    private void retryRegister(List<RegisterEvent.JobData> jobDataList, List<RegisterEvent.TriggerData> triggerDataList) {
        Config config = getContext().getSystem().settings().config();
        int count = 1;
        registerEvent = new RegisterEvent();
        ConfigObject configObject = config.getObject("akka.remote.netty.tcp");
        String hostname = configObject.get("hostname").unwrapped().toString();
        String portStr = configObject.get("port").unwrapped().toString();
        Integer port = Integer.valueOf(portStr);
        registerEvent.setIp(hostname);
        registerEvent.setPort(port);
        registerEvent.setJobDataList(jobDataList);
        registerEvent.setTriggerDataList(triggerDataList);
        //尝试三次注册
        do {
            //轮询每个master直到注册成功
            for (String masterStr : masterList) {
                String[] ipAndPort = masterStr.split(":");
                if (ObjectUtils.isEmpty(ipAndPort) || ipAndPort.length != 2) {
                    getContext().getSystem().terminate();
                    return;
                }
                String path = String.format(AKKA_REMOTE_MODEL, SCHEDULER_SYSTEM_NAME, ipAndPort[0], ipAndPort[1], SCHEDULER_REGISTER_NAME);
                ActorSelection actorSelection = getContext().actorSelection(path);
                Timeout t = new Timeout(Duration.create(2, TimeUnit.SECONDS));
                log.info("尝试第{}次注册，master地址：{}", count, path);
                //使用ask发送消息,actor处理完，必须有返回（超时时间2秒）
                CountDownLatch countDownLatch = new CountDownLatch(1);
                Future<Object> ask = Patterns.ask(actorSelection, registerEvent, t);
                ask.onComplete(new OnComplete<Object>() {
                    @Override
                    public void onComplete(Throwable throwable, Object o) throws Throwable {
                        String status = (String) o;
                        if (throwable != null) {
                            log.error("连接超时");
                        } else if (REGISTER_FAIL.equals(status)) {
                            log.error("注册失败，master遭遇异常");
                        } else {
                            registerSuccess = true;
                        }
                        countDownLatch.countDown();
                    }
                }, getContext().dispatcher());
                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (registerSuccess) {
                    log.info("注册成功：{}", masterStr);
                    //发送信息给heartbeat启动心跳
                    ActorRef actorRef = getContext().actorOf(HeartBeatActor.props(ipAndPort[0], ipAndPort[1], hostname, portStr), EXECUTOR_HEART_BEAT_NAME);
                    actorRef.tell(HEART_BEAT, getSelf());
                    return;
                }
            }
            count++;
            //睡眠一秒后重试
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } while (count < 4 && !registerSuccess);
        //如果注册失败停止ActorSystem
        getContext().getSystem().terminate();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ExecuteJobEvent.class, executeJobEvent -> {
                    execExecutorStartEvent(executeJobEvent);
                }).match(JobDetail.class, (jobDetail) -> {
                    init(jobDetail.getJobDataList(), jobDetail.getTriggerDataList());
                }).matchEquals(ACTIVE_JOB_EVENT, this::execFindActiveJob).build();
    }

    /**
     * 接受到需调度的任务，分配到executor执行
     *
     * @param executeJobEvent
     */
    private void execExecutorStartEvent(ExecuteJobEvent executeJobEvent) {
        //返回接收成功给master
        getSender().tell("OK", getSelf());
        executorRouter.tell(executeJobEvent, getSelf());
    }

    private void execFindActiveJob(String msg) {
        ActiveJobEvent activeJobEvent = new ActiveJobEvent();
        activeJobEvent.setJobNameList(this.registerEvent.getJobDataList().stream().map(RegisterEvent.JobData::getJobName).collect(Collectors.toList()));
        getSender().tell(activeJobEvent, getSelf());
    }
}
