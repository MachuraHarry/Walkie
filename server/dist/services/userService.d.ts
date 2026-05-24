import { User } from '../models/User';
export declare function loginUser(username: string): Promise<User>;
export declare function getUser(username: string): Promise<User | null>;
export declare function updateLastActive(username: string): Promise<void>;
//# sourceMappingURL=userService.d.ts.map