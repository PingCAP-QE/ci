import { parseArgs } from "jsr:@std/cli@^1.0.1";

const apiBaseURL = "https://open.feishu.cn/open-apis/";

export class LarkOpenAPI {
  constructor(private authorization: string) {}

  private newHeaders() {
    const headers = new Headers();
    headers.set("Authorization", this.authorization);
    return headers;
  }

  async searchUser(query: string) {
    const apiUrl = new URL("search/v1/user", apiBaseURL);
    apiUrl.search = new URLSearchParams({ query }).toString();

    const res = await fetch(apiUrl, { headers: this.newHeaders() });
    return await res.json();
  }
}

interface cliParams {
  token: string;
  githubId: string;
}

async function main({ token, githubId }: cliParams) {
  const api = new LarkOpenAPI(`Bearer ${token}`);

  const data = await api.searchUser(githubId);
  if (data.code !== 0) {
    console.error(data.msg);
    throw new Error("search user error");
  }
  switch (data.data.users.length) {
    case 0:
      console.log(`${githubId} => _NOT_FOUND_`);
      break;
    case 1:
      console.log(`${githubId} => ${data.data.users[0].name}`);
      break;
    default:
      console.log(`${githubId} => _MULTI_RESULTS_`);
      break;
  }
}

const cliArgs = parseArgs(Deno.args) as cliParams;
await main(cliArgs);
