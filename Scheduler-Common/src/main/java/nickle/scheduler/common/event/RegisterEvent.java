package nickle.scheduler.common.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * @author nickle
 * @description:
 * @date 2019/5/8 14:05
 */
@Data
public class RegisterEvent implements Serializable {
    private static final long serialVersionUID = -5966528694172083106L;

    @Data
    @EqualsAndHashCode(callSuper = false)
    @Accessors(chain = true)
    public static class JobData implements Serializable {
        private static final long serialVersionUID = 847501339257595821L;
        private String jobName;
        private String jobClassName;
        private String jobAuthor;
        private String jobTriggerName;
        private String jobDescription;
    }

    @Data
    @EqualsAndHashCode(callSuper = false)
    @Accessors(chain = true)
    public static class TriggerData implements Serializable {
        private static final long serialVersionUID = -6174291353571127754L;
        private String triggerName;
        private String triggerCron;
    }

    private String ip;
    private Integer port;
    private List<JobData> jobDataList;
    private List<TriggerData> triggerDataList;
}
