var http = require('http'),
	thrift = require('thrift'),
	fs = require('fs'),
	log4js = require('log4js');

/**
 * Configure thrift.
 */
var AppFabricService = require('./thrift_bindings/AppFabricService.js'),
	MetricsFrontendService = require('./thrift_bindings/MetricsFrontendService.js'),
	MetadataService = require('./thrift_bindings/MetadataService.js');

var metadataservice_types = require('./thrift_bindings/metadataservice_types.js'),
	metricsservice_types = require('./thrift_bindings/metricsservice_types.js'),
	appfabricservice_types = require('./thrift_bindings/app-fabric_types.js');

var SubscriptionService;
var ttransport, tprotocol;

try {
	ttransport = require('thrift/lib/thrift/transport');
} catch (e) {
	ttransport = require('thrift/transport');
}
try {
	tprotocol = require('thrift/lib/thrift/protocol');
} catch (e) {
	tprotocol = require('thrift/protocol');
}

/**
* Configure logger.
*/
const LOG_LEVEL = 'TRACE';
log4js.configure({
	appenders: [
		{ type : 'console' }
	]
});
var logger = process.logger = log4js.getLogger('Reactor UI API');
logger.setLevel(LOG_LEVEL);

/**
 * Export API.
 */
(function () {

	this.auth = null;
	this.config = null;

	this.configure = function (config) {
		this.config = config;
	};

	this.metadata = function (accountID, method, params, done) {

		params = params || [];

		var conn = thrift.createConnection(
			this.config['metadata.server.address'],
			this.config['metadata.server.port'], {
			transport: ttransport.TFramedTransport,
			protocol: tprotocol.TBinaryProtocol
		});
		conn.on('error', function (error) {
			done('Could not connect to MetadataService.');
		});

		var MetaData = thrift.createClient(MetadataService, conn);

		if (params.length === 2) {
			var entityType = params.shift();
			params[0] = new metadataservice_types[entityType](params[0]);
		}

		if (method.indexOf('ByApplication') !== -1 || method === 'getFlows' || method === 'getFlow' ||
			method === 'getFlowsByStream' || method === 'getFlowsByDataset') {
			params.unshift(accountID);
		} else {
			params.unshift(new metadataservice_types.Account({
				id: accountID
			}));
		}

		if (method in MetaData) {
			try {
				MetaData[method].apply(MetaData, params.concat(done));
			} catch (e) {
				done(e);
			}
		} else {
			done('Unknown method for MetadataService: ' + method, null);
		}
		
	},

	this.manager = function (accountID, method, params, done) {

		params = params || [];
		params.unshift(accountID);

		var auth_token = new appfabricservices_types.AuthToken({ token: null });

		var conn = thrift.createConnection(
			this.config['resource.manager.server.address'],
			this.config['resource.manager.server.port'], {
			transport: ttransport.TFramedTransport,
			protocol: tprotocol.TBinaryProtocol
		});

		conn.on('error', function (error) {
			done('Could not connect to FlowMonitor.');
		});
		
		var Manager = thrift.createClient(AppFabricService, conn);
		var identifier = new appfabricservice_types.FlowIdentifier({
			applicationId: params[1],
			flowId: params[2],
			version: params[3] ? parseInt(params[3], 10) : -1,
			accountId: params[0],
			type: appfabricservice_types.EntityType[params[4] || 'FLOW']
		});

		switch (method) {
			case 'start':
				identifier = new appfabricservice_types.FlowDescriptor({
					identifier: new appfabricservice_types.FlowIdentifier({
						applicationId: params[1],
						flowId: params[2],
						version: parseInt(params[3], 10),
						accountId: accountID,
						type: appfabricservice_types.EntityType[params[4] || 'FLOW']
					}),
					"arguments": []
				});
				Manager.start(auth_token, identifier, done);
			break;
			case 'stop':
				Manager.stop(auth_token, identifier, done);
			break;
			case 'status':
				Manager.status(auth_token, identifier, done);
			break;
			case 'getFlowDefinition':
				Manager.getFlowDefinition(identifier, done);
			break;
			case 'getFlowHistory':
				Manager.getFlowHistory(identifier, done);
			break;
			case 'setInstances':

				var flowlet_id = params[4];
				var instances = params[5];

				var identifier = new appfabricservice_types.FlowIdentifier({
					accountId: params[0],
					applicationId: params[1],
					flowId: params[2],
					version: params[3] || -1
				});

				Manager.setInstances(auth_token, identifier, flowlet_id, instances, done);

			break;

			default:
				if (method in Manager) {
					try {
						Manager[method].apply(Manager, params.concat(done));
					} catch (e) {
						done(e);
					}
				} else {
					done('Unknown method for service Manager: ' + method, null);
				}
				
		}

		conn.end();

	};

	this.far = function (accountID, method, params, done) {

		var identifier, conn, FAR,
			accountId = accountID,
			auth_token = new appfabricservice_types.AuthToken({ token: null });

		conn = thrift.createConnection(
			this.config['resource.manager.server.address'],
			this.config['resource.manager.server.port'], {
			transport: ttransport.TFramedTransport,
			protocol: tprotocol.TBinaryProtocol
		});

		conn.on('error', function (error) {
			done('Could not connect to AppFabricService');
		});
		
		FAR = thrift.createClient(AppFabricService, conn);
            
		switch (method) {

			case 'remove':

				identifier = new appfabricservice_types.FlowIdentifier({
					applicationId: params[0],
					flowId: params[1],
					version: params[2],
					accountId: accountID
				});
				FAR.remove(auth_token, identifier, done);
				break;

			case 'promote':

				identifier = new appfabricservice_types.FlowIdentifier({
					applicationId: params[0],
					flowId: params[1],
					version: params[2],
					accountId: accountID
				});
				FAR.promote(auth_token, identifier, done);

				break;

			case 'reset':

				FAR.reset(auth_token, accountId, done);

				break;

			}

			conn.end();

	};

	this.monitor = function (accountID, method, params, done) {

		params = params || [];

		var conn = thrift.createConnection(
			this.config['flow.monitor.server.address'],
			this.config['flow.monitor.server.port'], {
			transport: ttransport.TFramedTransport,
			protocol: tprotocol.TBinaryProtocol
		});

		conn.on('error', function (error) {
			done('Could not connect to FlowMonitor.');
		});
		
		conn.on('connect', function (response) {
			var Monitor = thrift.createClient(MetricsFrontendService, conn);

			switch (method) {
				case 'getLog':

					params.unshift(accountID);
					Monitor.getLog.apply(Monitor, params.concat(done));

				break;

				case 'getCounters':
					var flow = new metricsservice_types.FlowArgument({
						accountId: (params[0] === '-' ? '-' : accountID),
						applicationId: params[0],
						flowId: params[1],
						runId: params[2]
					});

					var names = params[3] || [];
					var request = new metricsservice_types.CounterRequest({
						argument: flow,
						name: names
					});
					Monitor.getCounters(request, done);
				break;
				case 'getTimeSeries':

					var level = params[5] || 'FLOW_LEVEL';

					var flow = new metricsservice_types.FlowArgument({
						accountId: (params[0] === '-' ? '-' : accountID),
						applicationId: params[0],
						flowId: params[1],
						flowletId: params[6] || null
					});
					var request = new metricsservice_types.TimeseriesRequest({
						argument: flow,
						metrics: params[2],
						level: metricsservice_types.MetricTimeseriesLevel[level],
						startts: params[3]
					});

					Monitor.getTimeSeries(request, function (error, response) {

						if (error) {
							done(true, false);
							return;
						}

						// Nukes timestamps since they're in Buffer format
						for (var metric in response.points) {

							var res = response.points[metric];
							if (res) {
								var j = res.length;
								while(j--) {
									res[j].timestamp = 0;
								}
								response.points[metric] = res;
							}
						}

						done(error, response);

					});

				break;
			}

			conn.end();
			
		});

	};

	this.gateway = function (apiKey, method, params, done) {

		var post_data = params.payload || "";

		var post_options = {};

		switch (method) {
			case 'inject':
				post_options = {
					host: this.config['gateway.hostname'],
					port: this.config['gateway.port']
				};
				post_options.method = 'POST';
				post_options.path = '/rest-stream' + (params.stream ? '/' + params.stream : '');
				post_options.headers = {
					'X-Continuuity-ApiKey': apiKey,
					'Content-Length': post_data.length
				};
			break;
			case 'query':
				post_options = {
					host: this.config['gateway.hostname'],
					port: 10003
				};
				post_options.method = 'GET';
				post_options.path = '/rest-query/' + params.service +
					'/' + params.method + (params.query ? '?' + params.query : '');
			break;
		}

		var post_req = http.request(post_options, function(res) {
			res.setEncoding('utf8');
			var data = [];

			res.on('data', function (chunk) {
				data.push(chunk);
			});
			res.on('end', function () {
				data = data.join('');
				done(res.statusCode !== 200 ? {
					statusCode: res.statusCode,
					response: data
				} : false, {
					statusCode: res.statusCode,
					response: data
				});
			});
		});

		post_req.on('error', function (e) {
			done(e, null);
		});

		if (method === 'inject') {
			post_req.write(post_data);
		}

		post_req.end();

	};

	this.upload = function (accountID, req, res, file, socket) {
		var self = this;
		var auth_token = new appfabricservice_types.AuthToken({ token: null });
		var length = req.header('Content-length');

		var data = new Buffer(parseInt(length, 10));
		var idx = 0;

		req.on('data', function(raw) {
			raw.copy(data, idx);
			idx += raw.length;
		});

		req.on('end', function() {
                        console.log("Upload ended");
			res.redirect('back');
			res.end();

			var conn = thrift.createConnection(
				self.config['resource.manager.server.address'],
				self.config['resource.manager.server.port'], {
				transport: ttransport.TFramedTransport,
				protocol: tprotocol.TBinaryProtocol
			});
			conn.on('error', function (error) {
				socket.emit('upload', {'error': 'Could not connect to AppFabricService'});
			});

			var FAR = thrift.createClient(AppFabricService, conn);
			FAR.init(auth_token, new appfabricservice_types.ResourceInfo({
				'accountId': accountID,
				'applicationId': 'nil',
				'filename': file,
				'size': data.length,
				'modtime': new Date().getTime()
			}), function (error, resource_identifier) {
				if (error) {
					logger.warn('AppFabric Init', error);
				} else {

					socket.emit('upload', {'status': 'Initialized...', 'resource_identifier': resource_identifier});

					var send_deploy = function () {

						socket.emit('upload', {'status': 'Deploying...'});

						FAR.deploy(auth_token, resource_identifier, function (error, result) {
							if (error) {
								logger.warn('FARManager deploy', error);
							} else {
								socket.emit('upload', {step: 0, 'status': 'Verifying...', result: arguments});

								var current_status = -1;

								var status_interval = setInterval(function () {
									FAR.status(auth_token, resource_identifier, function (error, result) {
										if (error) {
											logger.warn('FARManager verify', error);
										} else {

											if (current_status !== result.overall) {
												socket.emit('upload', {'status': 'verifying', 'step': result.overall, 'message': result.message, 'flows': result.verification});
												current_status = result.overall;
											}
											if (result.overall === 0 ||	// Not Found
												result.overall === 4 || // Failed
												result.overall === 5 || // Success
												result.overall === 6 || // Undeployed
												result.overall === 7) {
												clearInterval(status_interval);
											} // 1 (Registered), 2 (Uploading), 3 (Verifying)
										}
									});
								}, 500);
							}
						});

					};

					var send_chunk = function (index, size) {

						FAR.chunk(auth_token, resource_identifier, data.slice(index, index + size), function () {

							if (error) {
								socket.emit('Chunk error');
							} else {

								if (index + size === data.length) {
									send_deploy();

								} else {
									var length = size;
									if (index + (size * 2) > data.length) {
										length = data.length % size;
									}
									send_chunk(index + size, length);
								}
							}
						});
					};

					var CHUNK_SIZE = 102400;
					
					send_chunk(0, CHUNK_SIZE > data.length ? data.length : CHUNK_SIZE);

				}
			});
		});
	};
	
}).call(exports);
