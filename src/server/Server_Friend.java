package server;

import java.io.IOException;
import java.util.List;

import model.HibernateDataOperation;
import model.HibernateSessionFactory;
import model.ResultCode;
import model.User;

import org.apache.log4j.Logger;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;

import protocol.ProtoHead;
import protocol.Data.UserData;
import protocol.Data.UserData.UserItem;
import protocol.Msg.AddFriendMsg;
import protocol.Msg.ChangeFriendMsg;
import protocol.Msg.DeleteFriendMsg;
import protocol.Msg.GetUserInfoMsg;

import com.google.protobuf.InvalidProtocolBufferException;

import exception.NoIpException;

/**
 * 主服务器下的子服务器 负责通讯录相关事件
 * 
 * @author wangfei
 * 
 */
public class Server_Friend {
	Logger logger = Logger.getLogger(Server_Friend.class);

	private ServerModel serverModel;
	private ServerNetwork serverNetwork;

	public ServerModel getServerModel() {
		return serverModel;
	}

	public void setServerModel(ServerModel serverModel) {
		this.serverModel = serverModel;
	}

	public ServerNetwork getServerNetwork() {
		return serverNetwork;
	}

	public void setServerNetwork(ServerNetwork serverNetwork) {
		this.serverNetwork = serverNetwork;
	}

	/**
	 * 搜索用户
	 * 
	 * @param networkMessage
	 * @author wangfei
	 * @throws NoIpException
	 * @time 2015-03-23
	 */
	public void getUserInfo(PacketFromClient networkMessage) throws NoIpException {
		logger.info("Server_Friend.getUserInfo:begin to getUserInfo!");
		GetUserInfoMsg.GetUserInfoRsp.Builder getUserInfoBuilder = GetUserInfoMsg.GetUserInfoRsp.newBuilder();
		try {
			GetUserInfoMsg.GetUserInfoReq getUserInfoObject = GetUserInfoMsg.GetUserInfoReq.parseFrom(networkMessage
					.getMessageObjectBytes());

			ResultCode code = ResultCode.NULL;
			List list = HibernateDataOperation.query("userId", getUserInfoObject.getTargetUserId(), User.class, code);

			if (code.getCode().equals(ResultCode.SUCCESS) && list.size() > 0) {
				// 不支持模糊搜索 所以如果有搜索结果 只可能有一个结果
				User user = (User) list.get(0);
				UserData.UserItem.Builder userBuilder = UserData.UserItem.newBuilder();
				userBuilder.setUserId(user.getUserId());
				userBuilder.setUserName(user.getUserName());
				userBuilder.setHeadIndex(user.getHeadIndex());
				getUserInfoBuilder.setUserItem(userBuilder);
				getUserInfoBuilder.setResultCode(GetUserInfoMsg.GetUserInfoRsp.ResultCode.SUCCESS);
			} else if (code.getCode().equals(ResultCode.FAIL)) {
				logger.error("Server_Friend.getUserInfo: Hibernate error");
				getUserInfoBuilder.setResultCode(GetUserInfoMsg.GetUserInfoRsp.ResultCode.FAIL);
			} else if (list.size() < 1) {
				logger.info("Server_Friend.getUserInfo:User:" + ServerModel.getIoSessionKey(networkMessage.ioSession)
						+ " not exist!");
				getUserInfoBuilder.setResultCode(GetUserInfoMsg.GetUserInfoRsp.ResultCode.USER_NOT_EXIST);
			}
		} catch (InvalidProtocolBufferException e) {
			logger.error("Server_Friend.getUserInfo:Error was found when using Protobuf to deserialization "
					+ ServerModel.getIoSessionKey(networkMessage.ioSession) + " packet！");
			logger.error(e.getStackTrace());
			getUserInfoBuilder.setResultCode(GetUserInfoMsg.GetUserInfoRsp.ResultCode.FAIL);
		}
		try {
			// 回复客户端
			serverNetwork.sendMessageToClient(
					networkMessage.ioSession,
					PacketFromClient.packMessage(ProtoHead.ENetworkMessage.GET_USERINFO_RSP.getNumber(),
							networkMessage.getMessageID(), getUserInfoBuilder.build().toByteArray()));
		} catch (IOException e) {
			logger.error("Server_Friend.getUserInfo deal with user:" + ServerModel.getIoSessionKey(networkMessage.ioSession)
					+ " Send result Fail!");
			logger.error(e.getStackTrace());
		}
	}

	/**
	 * 添加好友
	 * 
	 * @param networkMessage
	 * @author wangfei
	 * @throws NoIpException 
	 * @time 2015-03-24
	 */
	public void addFriend(NetworkMessage networkMessage) throws NoIpException{
		logger.info("Server_Friend.addFriend:begin to add friend!");
		AddFriendMsg.AddFriendRsp.Builder addFriendBuilder = AddFriendMsg.AddFriendRsp.newBuilder();
		try {
			AddFriendMsg.AddFriendReq addFriendObject = AddFriendMsg.AddFriendReq.
					parseFrom(networkMessage.getMessageObjectBytes());
			ClientUser clientUser = ServerModel.instance.getClientUserFromTable(
					networkMessage.ioSession.getRemoteAddress().toString());
			User friend =null;
			ResultCode code1 = ResultCode.NULL;
			ResultCode code2 = ResultCode.NULL;
			List list1 = HibernateDataOperation.query("userId", clientUser.userId, User.class, code1);
			List list2 = HibernateDataOperation.query("userId", addFriendObject.getFriendUserId(), User.class, code2);
			User u = (User) list1.get(0);
			friend = (User) list2.get(0);
			if(code1.getCode().equals(ResultCode.FAIL) || code2.getCode().equals(ResultCode.FAIL)){
				//数据库查询失败 出现异常
				logger.error("Server_Friend.addFriend:Hibernate query fail");
				addFriendBuilder.setResultCode(AddFriendMsg.AddFriendRsp.ResultCode.FAIL);
			}
			else if(list1.size()<1 || list2.size()<1){
				//用户不存在
				logger.error("Server_Friend.addFriend:user or friend not exist "+list1.size()+" "+list2.size());
				addFriendBuilder.setResultCode(AddFriendMsg.AddFriendRsp.ResultCode.FAIL);
			}
			else{
				//查询结果正常 开始处理
				add(u,friend,clientUser,addFriendBuilder);
			}
			
		} catch (InvalidProtocolBufferException e) {
			logger.error("Server_Friend.addFriend:Error was found when using Protobuf to deserialization "+ ServerModel.getIoSessionKey(networkMessage.ioSession) + " packet！");
			logger.error(e.getStackTrace());
			addFriendBuilder.setResultCode(AddFriendMsg.AddFriendRsp.ResultCode.FAIL);
		}
		try{
			//回复客户端
			ServerNetwork.instance.sendMessageToClient(networkMessage.ioSession,NetworkMessage.packMessage(
					ProtoHead.ENetworkMessage.ADD_FRIEND_RSP.getNumber(),networkMessage.getMessageID(), addFriendBuilder.build().toByteArray()));
		}catch(IOException e){
			logger.error("Server_Friend.addFriend: deal with user:"+ServerModel.getIoSessionKey(networkMessage.ioSession)+" Send result Fail!");
			logger.error(e.getStackTrace());
		}
		
	}
	
	private void add(User u,User friend,ClientUser clientUser,AddFriendMsg.AddFriendRsp.Builder addFriendBuilder){
		//检测双方是否已经是好友关系
		boolean exist1 = false,exist2 = false;
		for(User user:u.getFriends()){
			if(user.getUserId().equals(friend.getUserId())){
				exist1 = true;
				break;
			}
		}
		for(User user:friend.getFriends()){
			if(user.getUserId().equals(u.getUserId())){
				exist2 = true ;
				break;
			}
		}
		//如果不存在好友关系 则添加好友
		ResultCode code1 = ResultCode.NULL;
		ResultCode code2 = ResultCode.NULL;
		if(!exist1){
			u.getFriends().add(friend);
		    HibernateDataOperation.update(u, code1);
		    //给添加好友的用户发送Sync
		    if(code1.getCode().equals(ResultCode.SUCCESS))
		    	sendSync(clientUser,friend,ChangeFriendMsg.ChangeFriendSync.ChangeType.ADD);
		}
		if(!exist2){
			friend.getFriends().add(u);
			HibernateDataOperation.update(friend, code2);
			
			ClientUser friendUser = ServerModel.instance.getClientUserByUserId(friend.getUserId());
			if(null != friendUser && code2.getCode().equals(ResultCode.SUCCESS)){
				//如果对方在线  需要发消息给对方通知好友添加
			   sendSync(friendUser,u,ChangeFriendMsg.ChangeFriendSync.ChangeType.ADD);
			}
		}
		if(code1.getCode().equals(ResultCode.SUCCESS) && code2.getCode().equals(ResultCode.SUCCESS))
			addFriendBuilder.setResultCode(AddFriendMsg.AddFriendRsp.ResultCode.SUCCESS);
		else
			addFriendBuilder.setResultCode(AddFriendMsg.AddFriendRsp.ResultCode.FAIL);
	}
	
	/**
	 * 删除好友
	 * 
	 * @param networkMessage
	 * @author wangfei
	 * @throws NoIpException 
	 * @time 2015-03-24
	 */
	public void deleteFriend(NetworkMessage networkMessage) throws NoIpException{
		logger.info("Server_Friend.deleteFriend:begin to delete friend!");
		DeleteFriendMsg.DeleteFriendRsp.Builder deleteFriendBuilder = DeleteFriendMsg.DeleteFriendRsp.newBuilder();
		try {
			DeleteFriendMsg.DeleteFriendReq deleteFriendObject = DeleteFriendMsg.DeleteFriendReq.
					parseFrom(networkMessage.getMessageObjectBytes());
			
			ClientUser clientUser = ServerModel.instance.getClientUserFromTable(
					networkMessage.ioSession.getRemoteAddress().toString());
			User friend = null;
			ResultCode code1 = ResultCode.NULL;
			ResultCode code2 = ResultCode.NULL;
			List list1 = HibernateDataOperation.query("userId", clientUser.userId, User.class, code1);
			List list2 = HibernateDataOperation.query("userId", deleteFriendObject.getFriendUserId(), User.class, code2);
			User u = (User)list1.get(0);
			friend = (User) list2.get(0);
			
			if(code1.getCode().equals(ResultCode.FAIL) || code2.getCode().equals(ResultCode.FAIL)){
				//数据库查询失败 出现异常
				logger.error("Server_Friend.deleteFriend:Hibernate query fail");
				deleteFriendBuilder.setResultCode(DeleteFriendMsg.DeleteFriendRsp.ResultCode.FAIL);
			}
			else if(list1.size()<1 || list2.size()<1){
				//用户不存在
				logger.error("Server_Friend.deleteFriend:user or friend not exist "+list1.size()+" "+list2.size());
				deleteFriendBuilder.setResultCode(DeleteFriendMsg.DeleteFriendRsp.ResultCode.FAIL);
			}
			else{
				delete(u,friend,clientUser,deleteFriendBuilder);
			}	
		} catch (InvalidProtocolBufferException e) {
			logger.error("Server_Friend.deleteFriend:Error was found when using Protobuf to deserialization "+ ServerModel.getIoSessionKey(networkMessage.ioSession) + " packet！");
			logger.error(e.getStackTrace());
			deleteFriendBuilder.setResultCode(DeleteFriendMsg.DeleteFriendRsp.ResultCode.FAIL);
		}
		try{
			//回复客户端
			ServerNetwork.instance.sendMessageToClient(networkMessage.ioSession,NetworkMessage.packMessage(
					ProtoHead.ENetworkMessage.DELETE_FRIEND_RSP.getNumber(),networkMessage.getMessageID(), deleteFriendBuilder.build().toByteArray()));
		}catch(IOException e){
			logger.error("Server_Friend.deleteFriend: deal with user:"+ServerModel.getIoSessionKey(networkMessage.ioSession)+" Send result Fail!");
			logger.error(e.getStackTrace());
		}
	}
	
	private void delete(User u,User friend,ClientUser clientUser,DeleteFriendMsg.DeleteFriendRsp.Builder deleteFriendBuilder){
		//检测双方之前是否是好友关系
		User x=null,y=null;
		for(User a:u.getFriends()){
			if(a.getUserId().equals(friend.getUserId()))
				x=a;
		}
		for(User b:friend.getFriends()){
			if(b.getUserId().equals(u.getUserId()))
				y=b;
		}
		//如果是存在好友关系 则删除
		ResultCode code1 = ResultCode.NULL;
		ResultCode code2 = ResultCode.NULL;
		if(null!=x){
			u.getFriends().remove(x);
			HibernateDataOperation.update(u, code1);
			if(code1.getCode().equals(ResultCode.SUCCESS))
				//给删除好友的用户发送Sync
				sendSync(clientUser,friend,ChangeFriendMsg.ChangeFriendSync.ChangeType.DELETE);
		}
		if(null != y){
			friend.getFriends().remove(y);
			HibernateDataOperation.update(friend, code2);
			ClientUser friendUser = ServerModel.instance.getClientUserByUserId(friend.getUserId());
			if(null != friendUser && code2.getCode().equals(ResultCode.SUCCESS))
				//如果被删除的用户在线 则给其发送Sync
				sendSync(friendUser,u,ChangeFriendMsg.ChangeFriendSync.ChangeType.DELETE);
		}
		if(code1.getCode().equals(ResultCode.SUCCESS) && code2.getCode().equals(ResultCode.SUCCESS))
			deleteFriendBuilder.setResultCode(DeleteFriendMsg.DeleteFriendRsp.ResultCode.SUCCESS);
		else
			deleteFriendBuilder.setResultCode(DeleteFriendMsg.DeleteFriendRsp.ResultCode.FAIL);
	}
	
	private void sendSync(ClientUser clientUser,User user,ChangeFriendMsg.ChangeFriendSync.ChangeType type){
		 UserItem.Builder uib = UserItem.newBuilder();
		 uib.setUserId(user.getUserId());
		 uib.setUserName(user.getUserName());
		 uib.setHeadIndex(user.getHeadIndex());
		 ChangeFriendMsg.ChangeFriendSync.Builder cfb = ChangeFriendMsg.ChangeFriendSync.newBuilder();
		 cfb.setChangeType(type);
		 cfb.setUserItem(uib);
		 //向客户端发送消息
		 byte[] messageBytes;
		try {
			messageBytes = NetworkMessage.packMessage(ProtoHead.ENetworkMessage.CHANGE_FRIEND_SYNC.getNumber(), cfb.build().toByteArray());
			 ServerNetwork.instance.sendMessageToClient(clientUser.ioSession, messageBytes);
			 // 添加等待回复
			 ServerModel.instance.addClientResponseListener(clientUser.ioSession, NetworkMessage.getMessageID(messageBytes),messageBytes, null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
