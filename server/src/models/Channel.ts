export interface Channel {
  id: number;
  name: string;
  created_by: string;
  created_at: Date;
  is_active: boolean;
  description?: string;
  color?: string;
}

export interface ChannelMember {
  id: number;
  channel_id: number;
  username: string;
  joined_at: Date;
}
