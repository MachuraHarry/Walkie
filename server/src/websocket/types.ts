// Nachricht vom Client zum Server
export interface ClientMessage {
  type: 'login' | 'create_channel' | 'join_channel' | 'leave_channel' |
        'get_channels' | 'get_users' | 'signal' | 'start_talking' | 'stop_talking';
  payload: any;
}

// Nachricht vom Server zum Client
export interface ServerMessage {
  type: 'login_success' | 'login_error' | 'channel_created' | 'channel_list' |
        'user_joined' | 'user_left' | 'user_list' | 'signal' | 'error' |
        'user_talking' | 'user_stopped_talking';
  payload: any;
}

// Signal-Nachricht für WebRTC
export interface SignalMessage {
  type: 'offer' | 'answer' | 'ice_candidate';
  from: string;
  to: string;
  channelId: number;
  data: any; // SDP oder ICE-Candidate
}

// Talking-Status
export interface TalkingStatus {
  username: string;
  channelId: number;
  isTalking: boolean;
}
