/**
 * Copyright &copy; 2021-2026 <a href="http://www.jeeplus.org/">JeePlus</a> All rights reserved.
 */
package com.jeeplus.flowable.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jeeplus.common.utils.Collections3;
import com.jeeplus.flowable.constant.FlowableConstant;
import com.jeeplus.flowable.vo.*;
import com.jeeplus.flowable.common.cmd.BackUserTaskCmd;
import com.jeeplus.flowable.model.Flow;
import com.jeeplus.flowable.model.TaskComment;
import com.jeeplus.flowable.mapper.FlowMapper;
import com.jeeplus.flowable.service.converter.json.FlowModelService;
import com.jeeplus.flowable.utils.FlowableUtils;
import com.jeeplus.flowable.utils.ProcessDefCache;
import com.jeeplus.sys.service.dto.UserDTO;
import com.jeeplus.sys.utils.UserUtils;
import lombok.extern.slf4j.Slf4j;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.Process;
import org.flowable.editor.language.json.converter.util.CollectionUtils;
import org.flowable.engine.*;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.flowable.task.service.impl.persistence.entity.TaskEntityImpl;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ??????????????????Service
 *
 * @author jeeplus
 * @version 2021-09-03
 */
@Slf4j
@Service
@Transactional
public class FlowTaskService {

    @Autowired
    private FlowMapper flowMapper;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private FormService formService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private IdentityService identityService;
    @Autowired
    private FlowModelService flowModelService;
    @Autowired
    private ManagementService managementService;
    @Autowired
    private FlowProcessService flowProcessService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * ????????????????????????
     *
     * @return
     */
    public Page <ProcessVo> todoList(Page<ProcessVo> page, Flow flow) {
        List<HashMap<String, String>> result = new ArrayList<HashMap<String, String>> ();
        String userId = UserUtils.getCurrentUserDTO ().getId ();//ObjectUtils.toString(UserUtils.getUser().getId());
        // =============== ???????????????????????????????????????  ===============
        TaskQuery todoTaskQuery = taskService.createTaskQuery ().taskCandidateOrAssigned (userId).active ()
                .includeProcessVariables ().orderByTaskCreateTime ().desc ();

        // ??????????????????
        if ( StrUtil.isNotBlank (flow.getProcDefKey ())) {
            todoTaskQuery.processDefinitionKey (flow.getProcDefKey ());
        }
        if (flow.getBeginDate () != null) {
            todoTaskQuery.taskCreatedAfter (flow.getBeginDate ());
        }
        if (flow.getEndDate () != null) {
            todoTaskQuery.taskCreatedBefore (flow.getEndDate ());
        }
        if (StrUtil.isNotBlank (flow.getTitle ())) {
            todoTaskQuery.processVariableValueLike ( FlowableConstant.TITLE, "%" + flow.getTitle () + "%");
        }


        long total = todoTaskQuery.count ( );
        page.setTotal ( total );

        List <Task> todoList;
        // ????????????
        if ( page.getSize ( ) == -1 ) {//?????????
            todoList = todoTaskQuery.list ( );
        } else {
            int start = (int) ((page.getCurrent ( ) - 1) * page.getSize ( ));
            int size = (int) (page.getSize ( ));
            todoList = todoTaskQuery.listPage ( start, size );
        }
        List records = Lists.newArrayList ();
        for (Task task : todoList) {
            Process process = SpringUtil.getBean (RepositoryService.class).getBpmnModel (task.getProcessDefinitionId ()).getMainProcess ();
            ProcessVo processVo = new ProcessVo ();
            TaskVo taskVo = new TaskVo (task);
            taskVo.setProcessDefKey (process.getId ());
            processVo.setTask (taskVo);
            processVo.setVars (task.getProcessVariables ());
            processVo.setProcessDefinitionName ( ProcessDefCache.get (task.getProcessDefinitionId ()).getName ());
            processVo.setVersion (ProcessDefCache.get (task.getProcessDefinitionId ()).getVersion ());
            processVo.setStatus ("todo");
            records.add ( processVo );
        }
        page.setRecords ( records );
        return page;
    }

    /**
     * ????????????????????????
     *
     * @param page
     * @return
     */
    public Page<HisTaskVo> historicList(Page<HisTaskVo> page, Flow act) {
        String userId = UserUtils.getCurrentUserDTO ().getId ();

        HistoricTaskInstanceQuery histTaskQuery = historyService.createHistoricTaskInstanceQuery ().taskAssignee (userId).finished ()
                .includeProcessVariables ().orderByHistoricTaskInstanceEndTime ().desc ();

        // ??????????????????
        if (StrUtil.isNotBlank (act.getProcDefKey ())) {
            histTaskQuery.processDefinitionKey (act.getProcDefKey ());
        }
        if (act.getBeginDate () != null) {
            histTaskQuery.taskCompletedAfter (act.getBeginDate ());
        }
        if (act.getEndDate () != null) {
            histTaskQuery.taskCompletedBefore (act.getEndDate ());
        }
        if (act.getTitle () != null) {
            histTaskQuery.processVariableValueLike (FlowableConstant.TITLE, "%" + act.getTitle () + "%");
        }

        // ????????????
        page.setTotal (histTaskQuery.count ());
        // ????????????
        List<HistoricTaskInstance> histList;
        if (page.getSize () == -1) {
            histList = histTaskQuery.list ();
        } else {
            int start = (int) ((page.getCurrent ( ) - 1) * page.getSize ( ));
            int size = (int) (page.getSize ( ));
            histList = histTaskQuery.listPage (start, size);
        }

        List records = Lists.newArrayList ();
        for (HistoricTaskInstance histTask : histList) {
            HisTaskVo hisTaskVo= new HisTaskVo (histTask);
            hisTaskVo.setProcessDefinitionName ( ProcessDefCache.get (histTask.getProcessDefinitionId ()).getName ());
            hisTaskVo.setBack (isBack (histTask));
            List<Task> currentTaskList = taskService.createTaskQuery ().processInstanceId (histTask.getProcessInstanceId ()).list ();
            if(((List) currentTaskList).size () > 0){
                TaskVo currentTaskVo =  new TaskVo (currentTaskList.get (0));
                hisTaskVo.setCurrentTask (currentTaskVo);
            }



            // ????????????????????????

            List<TaskComment> commentList = this.getTaskComments (histTask.getId ());
            if (commentList.size () > 0) {
                TaskComment comment = commentList.get (commentList.size ()-1);
                hisTaskVo.setComment (comment.getMessage ());
                hisTaskVo.setLevel (comment.getLevel ());
                hisTaskVo.setType (comment.getType ());
                hisTaskVo.setStatus (comment.getStatus ());

            }
            records.add ( hisTaskVo );
        }
        page.setRecords ( records );
        return page;
    }

    /**
     * ??????????????????????????????
     *
     * @param procInsId ????????????
     */
    public List<Flow> historicTaskList(String procInsId) throws Exception {
        List<Flow> actList = Lists.newArrayList ();
        List<HistoricActivityInstance> list = Lists.newArrayList ();
        List<HistoricActivityInstance> historicActivityInstances2 = historyService.createHistoricActivityInstanceQuery ().processInstanceId (procInsId)
                .orderByHistoricActivityInstanceStartTime ().asc ().orderByHistoricActivityInstanceEndTime ().asc ().list ();
        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances2) {
            if (historicActivityInstance.getEndTime () != null) {
                list.add (historicActivityInstance);
            }
        }

        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances2) {
            if (historicActivityInstance.getEndTime () == null) {
                list.add (historicActivityInstance);
            }
        }

        for (int i = 0; i < list.size (); i++) {
            HistoricActivityInstance histIns = list.get (i);
            // ????????????????????????????????????????????????????????????????????????
            if (StrUtil.isNotBlank (histIns.getAssignee ())
                    && historyService.createHistoricTaskInstanceQuery ().taskId (histIns.getTaskId ()).count () != 0
                    || BpmnXMLConstants.ELEMENT_TASK_USER.equals (histIns.getActivityType ()) && histIns.getEndTime () == null
                    || BpmnXMLConstants.ELEMENT_EVENT_START.equals (histIns.getActivityType ())
                    || BpmnXMLConstants.ELEMENT_EVENT_END.equals (histIns.getActivityType ())) {
                // ???????????????????????????
                Flow e = queryTaskState (histIns);

                actList.add (e);
            }
        }
        return actList;
    }

    /**
     * ???????????????????????????????????????????????????KEY?????????????????????????????????????????????KEY???
     *
     * @return
     */
    public String getFormKey(String procDefId, String taskDefKey) {
        String formKey = "";
        if (StrUtil.isNotBlank (procDefId)) {
            if (StrUtil.isNotBlank (taskDefKey)) {
                try {
                    formKey = formService.getTaskFormKey (procDefId, taskDefKey);
                } catch (Exception e) {
                    formKey = "";
                }
            }
            if (StrUtil.isBlank (formKey)) {
                formKey = formService.getStartFormKey (procDefId);
            }
            if (StrUtil.isBlank (formKey)) {
                formKey = "/404";
            }
        }
        log.debug ("getFormKey: {}", formKey);
        return formKey;
    }

    /**
     * ???????????????????????????????????????
     *
     * @param procInsId
     * @return
     */
    public ProcessInstance getProcIns(String procInsId) {
        return runtimeService.createProcessInstanceQuery ().processInstanceId (procInsId).singleResult ();
    }

    /**
     * ????????????????????????????????????
     *
     * @param procInsId
     * @return
     */
    public HistoricProcessInstance getFinishedProcIns(String procInsId) {
        return historyService.createHistoricProcessInstanceQuery ().processInstanceId (procInsId).singleResult ();
    }


    /**
     * ????????????????????????????????????
     *
     * @param user
     * @return
     */
    public Page<ProcessVo> getMyStartedProcIns(UserDTO user, Page<ProcessVo> page, Flow flow) throws Exception {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery ().startedBy (user.getId ()).includeProcessVariables ().orderByProcessInstanceStartTime ().desc ();
        if (flow.getBeginDate () != null) {
            query.startedAfter (flow.getBeginDate ());
        }
        if (flow.getEndDate () != null) {
            query.startedBefore (flow.getEndDate ());
        }
        if (StrUtil.isNotBlank (flow.getTitle ())) {
            query.variableValueLike (FlowableConstant.TITLE, "%" + flow.getTitle () + "%");
        }

        page.setTotal (query.count ());
        List<HistoricProcessInstance> histList;
        if (page.getSize () == -1) {//?????????
            histList = query.list ();
        } else {
            int start = (int) ((page.getCurrent ( ) - 1) * page.getSize ( ));
            int size = (int) (page.getSize ( ));
            histList = query.involvedUser (user.getId ()).listPage (start, size);
        }
        List records = Lists.newArrayList ();
        for (HistoricProcessInstance historicProcessInstance : histList) {
            ProcessVo processVo =  flowProcessService.queryProcessState (historicProcessInstance.getProcessDefinitionId (), historicProcessInstance.getId ());
            processVo.setEndTime (historicProcessInstance.getEndTime ());
            processVo.setStartTime (historicProcessInstance.getStartTime ());
            processVo.setProcessDefinitionId (historicProcessInstance.getProcessDefinitionId ());
            processVo.setProcessInstanceId (historicProcessInstance.getId ());
            processVo.setVars (historicProcessInstance.getProcessVariables ());
            processVo.setProcessDefinitionName (historicProcessInstance.getProcessDefinitionName ());
            processVo.setVersion ( historicProcessInstance.getProcessDefinitionVersion ());
            records.add (processVo);
        }
        page.setRecords ( records );

        return page;
    }


    /**
     * ????????????
     *
     * @param procDefKey    ????????????KEY
     * @param businessTable ???????????????
     * @param businessId    ???????????????
     * @param title         ??????????????????????????????????????????
     * @return ????????????ID
     */
    public String startProcess(String procDefKey, String businessTable, String businessId, String title) {
        Map<String, Object> vars = Maps.newHashMap ();
        return startProcess (procDefKey, businessTable, businessId, title, vars);
    }

    /**
     * ????????????
     *
     * @param procDefKey    ????????????KEY
     * @param businessTable ???????????????
     * @param businessId    ???????????????
     * @param title         ??????????????????????????????????????????
     * @param vars          ????????????
     * @return ????????????ID
     */
    @SuppressWarnings("unused")
    public String startProcess(String procDefKey, String businessTable, String businessId, String title, Map<String, Object> vars) {
        //String userId = UserUtils.getUser().getLoginName();//ObjectUtils.toString(UserUtils.getUser().getId())
        // ??????????????????
        if (vars == null) {
            vars = Maps.newHashMap ();
        }

        String userId = (String) vars.get (FlowableConstant.INITIATOR);
        if (userId == null) {
            userId = UserUtils.getCurrentUserDTO ().getId ();
        }
        String userName = UserUtils.get (userId).getName ();
        vars.put (FlowableConstant.USERNAME, userName);

        // ?????????????????????????????????ID???????????????????????????ID?????????activiti:initiator???
        identityService.setAuthenticatedUserId (userId);

        // ??????????????????
        if (StrUtil.isNotBlank (title)) {
            vars.put (FlowableConstant.TITLE, title);
        }

        // ????????????
        ProcessInstance procIns = runtimeService.startProcessInstanceByKey (procDefKey, businessTable + ":" + businessId, vars);

        // ???????????????????????????ID
        Flow act = new Flow ();
        act.setBusinessTable (businessTable);// ????????????
        act.setBusinessId (businessId);  // ?????????ID
        act.setProcInsId (procIns.getId ());
        act.setVars (vars);
        flowMapper.updateProcInsIdByBusinessId (act);
        return act.getProcInsId ();
    }


    /**
     * ????????????
     *
     * @param taskId       ??????ID
     * @param deleteReason ????????????
     */
    public void deleteTask(String taskId, String deleteReason) {
        taskService.deleteTask (taskId, deleteReason);
    }

    /**
     * ????????????
     *
     * @param taskId ??????ID
     * @param userId ????????????ID?????????????????????
     */
    public void claim(String taskId, String userId) {
        taskService.claim (taskId, userId);
    }


    /**
     * ????????????, ???????????????
     *
     * @param vars      ????????????
     */
    public void complete(Flow flow, Map<String, Object> vars) {
        // ????????????
        if (StrUtil.isNotBlank (flow.getProcInsId ())) {
            taskService.addComment (flow.getTaskId (), flow.getProcInsId (),flow.getComment ().getCommentType (), flow.getComment ().getFullMessage ());
        }

        // ??????????????????
        if (vars == null) {
            vars = Maps.newHashMap ();
        }

        // ??????????????????
        if (StrUtil.isNotBlank (flow.getTitle ())) {
            vars.put (FlowableConstant.TITLE, flow.getTitle ());
        }


        Task task = taskService.createTaskQuery ().taskId (flow.getTaskId ()).singleResult ();
        // owner???????????????????????????????????????
        if (StrUtil.isNotBlank (task.getOwner ())) {
            DelegationState delegationState = task.getDelegationState ();
            switch (delegationState) {
                case PENDING:
                    taskService.resolveTask (flow.getTaskId ());
                    taskService.complete (flow.getTaskId (), vars);
                    break;

                case RESOLVED:
                    // ????????????????????????
                    break;

                default:
                    // ??????????????????
                    taskService.complete (flow.getTaskId (), vars);
                    break;
            }
        } else if(StrUtil.isBlank (task.getAssignee ()))  { // ???????????????
            // ????????????
            taskService.claim (flow.getTaskId (), UserUtils.getCurrentUserDTO ().getId ());
            // ????????????
            taskService.complete (flow.getTaskId (), vars);
        } else  {
            // ????????????
            taskService.complete (flow.getTaskId (), vars);
        }
    }


    /**
     * ???????????????????????????
     */
    public Flow queryTaskState(HistoricActivityInstance histIns) {
        Flow e = new Flow ();
        e.setHistIns (histIns);
        // ???????????????????????????
        if (BpmnXMLConstants.ELEMENT_EVENT_START.equals (histIns.getActivityType ())) {
            List<HistoricProcessInstance> il = historyService.createHistoricProcessInstanceQuery ().processInstanceId (histIns.getProcessInstanceId ()).orderByProcessInstanceStartTime ().asc ().list ();
            if (il.size () > 0) {
                if (StrUtil.isNotBlank (il.get (0).getStartUserId ())) {
                    UserDTO user = UserUtils.get (il.get (0).getStartUserId ());
                    if (user != null) {
                        e.setAssignee (histIns.getAssignee ());
                        e.setAssigneeName (user.getName ());
                    }
                }
            }
            TaskComment taskComment = new TaskComment ();
            taskComment.setStatus (FlowableConstant.START_EVENT_LABEL);
            taskComment.setMessage (FlowableConstant.START_EVENT_COMMENT);
            e.setComment (taskComment);
            return e;
        }
        if (BpmnXMLConstants.ELEMENT_EVENT_END.equals (histIns.getActivityType ())) {
            TaskComment taskComment = new TaskComment ();
            taskComment.setStatus (FlowableConstant.END_EVENT_LABEL);
            taskComment.setMessage (FlowableConstant.END_EVENT_COMMENT);
            e.setAssigneeName (FlowableConstant.SYSTEM_EVENT_COMMENT);
            e.setComment (taskComment);
            return e;
        }
        // ???????????????????????????
        if (StrUtil.isNotEmpty (histIns.getAssignee ())) {
            UserDTO user = UserUtils.get (histIns.getAssignee ());
            if (user != null) {
                e.setAssignee (histIns.getAssignee ());
                e.setAssigneeName (user.getName ());
            }
        }
        // ????????????????????????
        if (StrUtil.isNotBlank (histIns.getTaskId ())) {
            List<TaskComment> commentList = this.getTaskComments (histIns.getTaskId ());
            HistoricVariableInstanceQuery action = historyService.createHistoricVariableInstanceQuery ().processInstanceId (histIns.getProcessInstanceId ()).taskId (histIns.getTaskId ()).variableName ("_flow_button_name");
            if (commentList.size () > 0) {
                TaskComment comment = commentList.get (commentList.size ()-1);
                e.setComment (comment);
            }else {
                e.setComment (new TaskComment ());
            }
        }
        //?????????????????????
        if(histIns.getEndTime () == null) {
            List assignList = Lists.newArrayList ();
            taskService.getIdentityLinksForTask ( histIns.getTaskId () ).forEach ( identityLink -> {
                assignList.add ( UserUtils.get ( identityLink.getUserId () ) );
            } );
            String assignName = Collections3.extractToString ( assignList, "name", ",");
            TaskComment taskComment = new TaskComment ();
            taskComment.setStatus ( ActionType.WAITING.getStatus ());
//            taskComment.setMessage (FlowableConstant.WAITING_EVENT_COMMENT);
            e.setComment (taskComment);
            e.setAssigneeName ( assignName );
        }
        return e;
    }


    public List<TaskComment> getTaskComments(String taskId){
        return jdbcTemplate.query("select * from ACT_HI_COMMENT where TYPE_ like '"+TaskComment.prefix+"%' and TASK_ID_ = '" + taskId + "' order by TIME_ desc", new RowMapper<TaskComment>() {
            @Override
            public TaskComment mapRow(ResultSet rs, int rowNum) throws SQLException {
                TaskComment taskComment = new TaskComment ();
                taskComment.setCommentType (rs.getString ("TYPE_"));
                taskComment.setFullMessage (new String(rs.getBytes ("FULL_MSG_")));
                return taskComment;
            }
        });
    }


    public Map getDiagram(String processId) {
        Map m = new HashMap ();
        try {
            String processDefId = "";
            ProcessInstance pi = runtimeService.createProcessInstanceQuery ().processInstanceId (processId).singleResult ();
            //???????????????????????????
            if (pi == null) {
                processDefId = historyService.createHistoricProcessInstanceQuery ().processInstanceId (processId).singleResult ().getProcessDefinitionId ();
            } else {
                processDefId = pi.getProcessDefinitionId ();
            }
            BpmnModel bpmnModel = repositoryService.getBpmnModel (processDefId);
            List<HistoricActivityInstance> historyProcess = getHistoryProcess (processId);
            Set<String> activityIds = new LinkedHashSet<> ();
            List<String> flows = new ArrayList<> ();
            for (HistoricActivityInstance hi : historyProcess) {
                String activityType = hi.getActivityType ();
                if (activityType.equals (BpmnXMLConstants.ELEMENT_SEQUENCE_FLOW) || activityType.equals (BpmnXMLConstants.ELEMENT_GATEWAY_EXCLUSIVE)) {
                    flows.add (hi.getActivityId ());
                } else  if (StrUtil.isNotBlank (hi.getAssignee ())
                        && historyService.createHistoricTaskInstanceQuery ().taskId (hi.getTaskId ()).count () != 0
                        || BpmnXMLConstants.ELEMENT_TASK_USER.equals (hi.getActivityType ()) && hi.getEndTime () == null
                        || BpmnXMLConstants.ELEMENT_EVENT_START.equals (hi.getActivityType ())
                        || BpmnXMLConstants.ELEMENT_EVENT_END.equals (hi.getActivityType ())) {
                    activityIds.add (hi.getActivityId ());
                }
            }
            List<Task> tasks = taskService.createTaskQuery ().processInstanceId (processId).list ();
            for (Task task : tasks) {
                activityIds.remove ( task.getTaskDefinitionKey () );
                activityIds.add (task.getTaskDefinitionKey ());
            }
            byte[] bpmnBytes = flowModelService.getBpmnXML (bpmnModel);
            m.put ("bpmnXml", new String (bpmnBytes));
            m.put ("flows", flows);
            m.put ("activityIds", activityIds);
            return m;
        } catch (Exception e) {
            e.printStackTrace ();
        }
        return null;
    }


    /**
     * ????????????
     *
     * @param processId ??????id
     */
    public List<HistoricActivityInstance> getHistoryProcess(String processId) {
        List<HistoricActivityInstance> list = historyService // ????????????Service
                .createHistoricActivityInstanceQuery () // ??????????????????????????????
                .processInstanceId (processId) // ??????????????????id
                .finished ().orderByHistoricActivityInstanceEndTime ().asc ()
                .list ();
        return list;
    }



    /**
     * ??????????????????
     *
     * @param flow
     */
    public void auditSave(Flow flow, Map vars) {
        complete (flow, vars);

    }


    /**
     * ????????????????????????
     */
    public boolean isBack(HistoricTaskInstance hisTask) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery ()
                .processInstanceId (hisTask.getProcessInstanceId ()).singleResult ();
        if (pi != null) {
            if (pi.isSuspended ()) {
                return false;
            } else {
                Task currentTask = taskService.createTaskQuery ().processInstanceId (hisTask.getProcessInstanceId ()).list ().get (0);
                HistoricTaskInstance lastHisTask = historyService.createHistoricTaskInstanceQuery ().processInstanceId (hisTask.getProcessInstanceId ()).finished ()
                        .includeProcessVariables ().orderByHistoricTaskInstanceEndTime ().desc ().list ().get (0);

                if (currentTask.getClaimTime () != null) {//???????????????
                    return false;
                }
                if (hisTask.getId ().equals (lastHisTask.getId ())) {
                    return true;
                }
                return false;
            }

        } else {
            return false;
        }
    }

    /*
     * ????????????
     */
    public String backTask(String backTaskDefKey, String taskId, TaskComment comment) {
        Task task = taskService.createTaskQuery ().taskId (taskId).singleResult ();
        if(StrUtil.isBlank (task.getAssignee ())){
            taskService.claim (taskId, UserUtils.getCurrentUserDTO ().getId ());
        }

        // ?????????????????????,??????????????????,???????????????????????????????????????
        ActivityInstance targetRealActivityInstance = runtimeService.createActivityInstanceQuery ().processInstanceId (task.getProcessInstanceId ()).activityId (backTaskDefKey).list ().get (0);
        if (targetRealActivityInstance.getActivityType ().equals (BpmnXMLConstants.ELEMENT_EVENT_START)) {
            flowProcessService.stopProcessInstanceById (task.getProcessInstanceId (), ProcessStatus.REJECT, comment.getMessage ());
        }else {
            taskService.addComment (taskId, task.getProcessInstanceId (), comment.getCommentType (), comment.getFullMessage ());
            managementService.executeCommand (new BackUserTaskCmd (runtimeService,
                    taskId, backTaskDefKey));
        }

        return task.getProcessInstanceId ();
    }


    /**
     * ?????????????????????
     *
     * @param taskId
     * @return
     */
    public List<Flow> getBackNodes(String taskId) {
        Task taskEntity = taskService.createTaskQuery ().taskId (taskId).singleResult ();
        String processInstanceId = taskEntity.getProcessInstanceId ();
        String currActId = taskEntity.getTaskDefinitionKey ();
        String processDefinitionId = taskEntity.getProcessDefinitionId ();
        Process process = repositoryService.getBpmnModel (processDefinitionId).getMainProcess ();
        FlowNode currentFlowElement = (FlowNode) process.getFlowElement (currActId, true);
        List<ActivityInstance> activitys =
                runtimeService.createActivityInstanceQuery ().processInstanceId (processInstanceId).finished ().orderByActivityInstanceStartTime ().asc ().list ();
        List<String> activityIds =
                activitys.stream ().filter (activity -> activity.getActivityType ().equals (BpmnXMLConstants.ELEMENT_TASK_USER) || activity.getActivityType ().equals (BpmnXMLConstants.ELEMENT_EVENT_START)).filter (activity -> !activity.getActivityId ().equals (currActId)).map (ActivityInstance::getActivityId).distinct ().collect (Collectors.toList ());
        List<Flow> result = new ArrayList<> ();
        for (String activityId : activityIds) {
            FlowNode toBackFlowElement = (FlowNode) process.getFlowElement (activityId, true);
            if (FlowableUtils.isReachable (process, toBackFlowElement, currentFlowElement)) {
                Flow vo = new Flow ();
                vo.setTaskDefKey (activityId);
                vo.setTaskName (toBackFlowElement.getName ());
                vo.setTaskId (activityId);
                result.add (vo);
            }
        }
        return result;
    }

    public void addSignTask(String taskId, List<String> userIds, String comment, Boolean flag) throws Exception {
        TaskEntityImpl taskEntity = (TaskEntityImpl) taskService.createTaskQuery().taskId(taskId).singleResult();
        //1.??????????????????????????????
        if (taskEntity != null) {
            //????????????????????????
            String parentTaskId = taskEntity.getParentTaskId();
            if (org.apache.commons.lang.StringUtils.isBlank(parentTaskId)) {
                taskEntity.setOwner(UserUtils.getCurrentUserDTO ().getId ());
                taskEntity.setAssignee(null);
                taskEntity.setCountEnabled(true);
                if (flag) {
                    taskEntity.setScopeType(FlowableConstant.AFTER_ADDSIGN);
                } else {
                    taskEntity.setScopeType(FlowableConstant.BEFORE_ADDSIGN);
                }
                //1.2 ???????????????????????????
                taskService.saveTask(taskEntity);
            }
            //2.??????????????????
            this.createSignSubTasks(userIds, taskEntity);
            //3.??????????????????
            String type = flag ? ActionType.ADD_AFTER_MULTI_INSTANCE.toString() : ActionType.ADD_BEFORE_MULTI_INSTANCE.toString();
            taskService.addComment (taskId, taskEntity.getProcessInstanceId (), type, comment);
        } else {
            throw  new Exception ("?????????????????????????????????!");
        }
    }

    /**
     * ?????????????????????
     *
     * @param userIds     ????????????
     * @param taskEntity ?????????
     */
    private void createSignSubTasks(List<String> userIds, TaskEntity taskEntity) {
        if (CollectionUtils.isNotEmpty(userIds)) {
            String parentTaskId = taskEntity.getParentTaskId();
            if (org.apache.commons.lang.StringUtils.isBlank(parentTaskId)) {
                parentTaskId = taskEntity.getId();
            }
            String finalParentTaskId = parentTaskId;
            //1.?????????????????????????????????
            userIds.forEach(userCode -> {
                if (StrUtil.isNotBlank(userCode)) {
                    this.createSubTask(taskEntity, finalParentTaskId, userCode);
                }
            });
            String taskId = taskEntity.getId();
            if (org.apache.commons.lang.StringUtils.isBlank(taskEntity.getParentTaskId())) {
                //2.???????????????????????????????????????
                Task task = this.createSubTask(taskEntity, finalParentTaskId, UserUtils.getCurrentUserDTO ().getId ());
                taskId = task.getId();
            }
            Task taskInfo = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (null != taskInfo) {
                taskService.complete(taskId);
            }
            //??????????????????????????????????????????????????????????????????
            long candidateCount = taskService.createTaskQuery().taskId(parentTaskId).taskCandidateUser(UserUtils.getCurrentUserDTO ().getId ()).count();
            if (candidateCount > 0) {
                taskService.deleteCandidateUser(parentTaskId, UserUtils.getCurrentUserDTO ().getId ());
            }
        }
    }

    /**
     * ???????????????
     *
     * @param ptask    ???????????????
     * @param assignee ?????????????????????
     * @return
     */
    protected TaskEntity createSubTask(TaskEntity ptask, String ptaskId, String assignee) {
        TaskEntity task = null;
        if (ptask != null) {
            //1.???????????????
            task = (TaskEntity) taskService.newTask(UUID.randomUUID ().toString ());
            task.setCategory(ptask.getCategory());
            task.setDescription(ptask.getDescription());
            task.setTenantId(ptask.getTenantId());
            task.setAssignee(assignee);
            task.setName(ptask.getName());
            task.setParentTaskId(ptaskId);
            task.setProcessDefinitionId(ptask.getProcessDefinitionId());
            task.setProcessInstanceId(ptask.getProcessInstanceId());
            task.setTaskDefinitionKey(ptask.getTaskDefinitionKey());
            task.setTaskDefinitionId(ptask.getTaskDefinitionId());
            task.setPriority(ptask.getPriority());
            task.setCreateTime(new Date());
            taskService.saveTask(task);
        }
        return task;
    }

}
