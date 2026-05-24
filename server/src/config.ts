export const config = {
  port: parseInt(process.env.PORT || '3000', 10),
  db: {
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432', 10),
    database: process.env.DB_NAME || 'walkie',
    user: process.env.DB_USER || 'walkie',
    password: process.env.DB_PASSWORD || 'walkie_secret',
  },
};
