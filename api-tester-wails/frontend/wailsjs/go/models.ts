export namespace main {
	
	export class SendRequest {
	    method: string;
	    url: string;
	    headers: Record<string, string>;
	    body: string;
	
	    static createFrom(source: any = {}) {
	        return new SendRequest(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.method = source["method"];
	        this.url = source["url"];
	        this.headers = source["headers"];
	        this.body = source["body"];
	    }
	}
	export class SendResponse {
	    status: number;
	    status_text: string;
	    headers: Record<string, string>;
	    body: string;
	    duration_ms: number;
	    error?: string;
	
	    static createFrom(source: any = {}) {
	        return new SendResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.status = source["status"];
	        this.status_text = source["status_text"];
	        this.headers = source["headers"];
	        this.body = source["body"];
	        this.duration_ms = source["duration_ms"];
	        this.error = source["error"];
	    }
	}

}

