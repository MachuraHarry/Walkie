import { Channel, ChannelMember } from '../models/Channel';
export declare function createChannel(name: string, description: string, color: string, createdBy: string): Promise<Channel>;
export declare function getChannels(): Promise<(Channel & {
    member_count: number;
})[]>;
export declare function joinChannel(channelId: number, username: string): Promise<boolean>;
export declare function leaveChannel(channelId: number, username: string): Promise<void>;
export declare function getChannelMembers(channelId: number): Promise<ChannelMember[]>;
export declare function getChannelById(channelId: number): Promise<Channel | null>;
//# sourceMappingURL=channelService.d.ts.map